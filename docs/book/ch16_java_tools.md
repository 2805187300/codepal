# 第16章：Java 工程化专项工具（新增）

> 目标：理解为什么通用 Coding Agent 处理 Java 项目时效率低下，三个专项工具（JavaBuild、JUnitRun、JavaDependency）各自解决什么问题，以及"首轮编译修复成功率提升"背后的工程原理。
> 对应简历亮点：第7条 — Java 工程化专项，首轮编译修复成功率提升，定向测试执行时间缩短至全量运行的 1/N
> 关键文件：`tool/impl/JavaBuildTool.java`、`tool/impl/JUnitRunTool.java`、`tool/impl/JavaDependencyTool.java`

---

## 16.1 问题：通用工具处理 Java 的痛点

没有专项工具时，Agent 处理 Java 项目只能靠 Bash：

```bash
# 编译
Bash("./gradlew compileJava")

# 输出（原始）：
> Task :compileJava FAILED
/src/main/java/com/example/Foo.java:42: error: cannot find symbol
    import com.example.bar.BarService;
/src/main/java/com/example/Foo.java:55: error: incompatible types: String cannot be converted to int
    int result = getValue();
2 errors
```

LLM 拿到这段输出要：
1. 识别这是 Gradle 还是 Maven 的输出格式
2. 解析错误，找到文件名 + 行号 + 错误类型
3. 决定先修哪个错误

**问题一：重复无效重试。** 如果 LLM 只看到最后几行输出，可能错过关键错误信息，修了一个错误，下一轮编译又报类似的错，反复循环。

**问题二：全量测试太慢。** 每次修改一个文件，Agent 跑 `./gradlew test`，把整个项目的测试全跑一遍，可能要几分钟。如果只是修了 `AgentTest.java`，只需要跑 `AgentTest` 一个类就够了。

**问题三：添加依赖前不知道是否已存在。** LLM 想加一个库，但不知道 `pom.xml` / `build.gradle` 里有没有，可能重复添加或者添加了冲突版本。

三个专项工具分别解决这三个问题。

---

## 16.2 JavaBuildTool：结构化编译输出

### 自动探测构建系统

```java
private List<String> buildCommand(Path workDir, String goal, String extraArgs) {
    boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

    if (Files.exists(workDir.resolve("pom.xml"))) {
        // Maven 项目
        return List.of(isWindows ? "mvn.cmd" : "mvn",
                       mapMavenGoal(goal),   // compile / test-compile / package / install
                       "-B");                // 批处理模式，输出更简洁
    } else if (Files.exists(workDir.resolve("build.gradle.kts"))
            || Files.exists(workDir.resolve("build.gradle"))) {
        // Gradle 项目：优先用 wrapper（./gradlew），保证版本一致
        File gradlew = workDir.resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();
        String executable = gradlew.exists() ? gradlew.getAbsolutePath() : "gradle";
        return List.of(executable, mapGradleTask(goal), "--console=plain");
    } else {
        // 没有构建文件，退化到 javac
        return List.of(isWindows ? "javac.exe" : "javac", "-version");
    }
}
```

**为什么用 `./gradlew` 而不是系统 `gradle`？**

项目的 `gradlew`（Gradle Wrapper）锁定了特定版本（在 `gradle/wrapper/gradle-wrapper.properties` 里）。系统安装的 `gradle` 可能版本不同，导致构建行为不一致。专业工程师的惯例也是用 `./gradlew`，符合实际开发习惯。

### 结构化输出

```java
// 统计错误和警告数量
int errorCount = countMatches(rawOutput, "ERROR", "error:");
int warningCount = countMatches(rawOutput, "WARNING", "warning:");

String summary = "Summary: " + errorCount + " error(s), " + warningCount + " warning(s)\n";
String truncated = truncate(rawOutput, MAX_OUTPUT_LENGTH);  // 最多 8000 字符

if (exitCode == 0) {
    return ToolResult.success("BUILD SUCCESS\n" + summary + truncated);
} else {
    return ToolResult.error("BUILD FAILED\n" + summary + truncated);
}
```

LLM 拿到的结果：
```
BUILD FAILED
Summary: 2 error(s), 0 warning(s)
> Task :compileJava FAILED
/src/.../Foo.java:42: error: cannot find symbol
/src/.../Foo.java:55: error: incompatible types
```

第一行明确是成功还是失败，第二行告诉 LLM "有 2 个错误需要修"。LLM 不需要解析原始 Gradle 输出，直接看结构化结果。

### 首轮修复成功率为什么提升？

Java 编译器（javac）是**一次性报告所有错误**的（不像某些语言遇到第一个错误就停止）。这意味着一次编译输出里可能有 10 个错误，都是独立的，也可能前 3 个都是同一个根因（比如一个类名写错了）。

通用 Bash 方案：
- LLM 看到原始输出，可能被格式噪声淹没，只看到一部分错误
- 修了可见的错误，下一轮还有看不见的

JavaBuild 的结构化输出：
- 明确说"2 个错误"，LLM 知道要修够 2 个
- 错误行按 `FILE:LINE: error: message` 格式清晰呈现
- `truncate(..., MAX_OUTPUT_LENGTH)` 保证输出不超过 8000 字符（避免超大输出占满上下文），但优先保留前几个错误（最关键的）

---

## 16.3 JUnitRunTool：定向测试执行

### 构建系统检测 + 测试过滤器格式

```java
// 检测构建系统
private BuildSystem detectBuildSystem(Path projectDir) {
    if (Files.exists(projectDir.resolve("pom.xml"))) return BuildSystem.MAVEN;
    if (Files.exists(projectDir.resolve("build.gradle")) ||
        Files.exists(projectDir.resolve("build.gradle.kts"))) return BuildSystem.GRADLE;
    return BuildSystem.NONE;
}

// 构建命令（注意 Maven 和 Gradle 的过滤器格式不同）
private List<String> buildCommand(BuildSystem bs, Path projectDir, String testFilter) {
    if (bs == BuildSystem.MAVEN) {
        List<String> cmd = new ArrayList<>(List.of("mvn", "test"));
        if (!testFilter.isEmpty()) {
            cmd.add("-Dtest=" + testFilter);  // Maven: "FooTest" 或 "FooTest#testMethod"
        }
        cmd.add("-Dsurefire.failIfNoSpecifiedTests=false");  // 过滤不匹配时不报错
        return cmd;
    } else {
        File gradlew = projectDir.resolve("gradlew").toFile();
        List<String> cmd = new ArrayList<>(List.of(
            gradlew.canExecute() ? "./gradlew" : "gradle", "test"));
        if (!testFilter.isEmpty()) {
            cmd.add("--tests");
            cmd.add(testFilter);  // Gradle: "com.example.FooTest.testMethod"
        }
        return cmd;
    }
}
```

**Maven 和 Gradle 的过滤器格式差异很重要：**

| 场景 | Maven | Gradle |
|------|-------|--------|
| 整个类 | `-Dtest=FooTest` | `--tests com.example.FooTest` |
| 单个方法 | `-Dtest=FooTest#testMethod` | `--tests com.example.FooTest.testMethod` |

注意：Maven 用 `#` 分隔类和方法，Gradle 用 `.`。这个差异很容易让 LLM 写出错误的命令，JavaBuildTool 把这个细节封装进工具，LLM 只需要传 `test_filter` 参数，不需要知道格式细节。

### 结构化测试结果解析

```java
// 识别 Maven Surefire 的输出格式
private static final Pattern MAVEN_SUMMARY =
    Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

// 识别 Gradle 的输出格式
private static final Pattern GRADLE_SUMMARY =
    Pattern.compile("(\\d+) tests? completed(?:,\\s*(\\d+) failed)?(?:,\\s*(\\d+) skipped)?");
```

LLM 拿到的结果：
```
=== Test Summary ===
Tests run: 15, Failures: 1, Errors: 0, Skipped: 2
Build status: FAILURE
Exit code: 1

=== Failed Tests ===
  FAILED: com.example.AgentTest > testAgentLoop
    AssertionError: expected:<3> but was:<2> at AgentTest.java:45

=== Raw Output (last 60 lines) ===
...
```

**定向执行节省时间的计算：**

假设项目有 200 个测试，全量跑 `./gradlew test` 需要 4 分钟。修改了 `Agent.java`，只需要跑 `AgentTest`（10 个测试），`--tests com.example.AgentTest` 只需 15 秒。时间缩短到原来的 1/16（即 1/N，N 是测试总数除以需要跑的测试数）。简历亮点里写的"缩短至全量运行的 1/N"就是这个含义。

---

## 16.4 JavaDependencyTool：依赖分析

### 三种 action

```java
private List<String> buildCommand(BuildTool buildTool, String action) {
    return switch (action) {
        case "list" -> switch (buildTool) {
            case MAVEN  -> List.of("mvn", "dependency:list", "-DincludeScope=compile", "-q");
            case GRADLE -> List.of("./gradlew", "dependencies", "--configuration", "compileClasspath", "-q");
            default -> null;
        };
        case "tree" -> switch (buildTool) {
            case MAVEN  -> List.of("mvn", "dependency:tree");
            case GRADLE -> List.of("./gradlew", "dependencies");
            default -> null;
        };
        case "check-updates" -> switch (buildTool) {
            // 只有 Maven 支持（Maven Versions Plugin）
            case MAVEN -> List.of("mvn", "versions:display-dependency-updates", "-q");
            default -> null;
        };
        default -> null;
    };
}
```

**为什么 JavaDependency 归类为 READ 而不是 COMMAND？**

```java
@Override
public ToolCategory category() {
    return ToolCategory.READ;  // ← 注意
}
```

`list` 和 `tree` 只是查询依赖，不修改文件系统，是只读操作。归类为 READ 意味着：如果主 Agent 同时要查依赖和读文件，这两个操作可以并发跑（第3章的并发分批机制），提高效率。

### 过滤下载进度输出

```java
private boolean isDownloadProgressLine(String line) {
    String trimmed = line.trim();
    return trimmed.startsWith("[INFO] Downloading")
            || trimmed.startsWith("[INFO] Downloaded")
            || trimmed.startsWith("Downloading:")
            || trimmed.startsWith("Downloaded:");
}
```

Maven 第一次运行时会下载依赖，输出大量"Downloading: xxx.jar"的行，这些对 LLM 没有意义，还占用输出空间。`cleanOutput` 方法过滤掉这些行，让 LLM 只看到真正的依赖列表。

---

## 16.5 三个工具的协作场景

一个典型的 Java bug 修复任务，三个工具如何配合：

```
1. Agent 读取代码，发现可能需要某个依赖
   JavaDependency(project_path=".", action="list")
   → 发现已经有了 jackson-databind 2.15，不需要重复添加

2. Agent 修改了 Foo.java
   EditFile(file_path="src/.../Foo.java", old_string="...", new_string="...")

3. Agent 验证修改能编译通过
   JavaBuild(project_path=".", goal="compile")
   → BUILD SUCCESS, Summary: 0 error(s), 0 warning(s)

4. Agent 只跑相关测试（不跑全量）
   JUnitRun(project_path=".", test_filter="com.example.FooTest")
   → Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
   → 验证通过，任务完成
```

**与通用 Bash 方案的对比：**

| 维度 | Bash 方案 | 专项工具方案 |
|------|----------|------------|
| 构建系统识别 | LLM 猜测或读 build 文件 | 自动检测（pom.xml / build.gradle） |
| 编译错误格式 | 原始输出，格式噪声多 | 结构化：文件:行:错误类型 |
| 测试过滤器格式 | LLM 要知道 Maven/Gradle 差异 | 统一接口，工具内部转换 |
| 下载进度噪声 | 混在输出里 | 自动过滤 |
| 工具调度优化 | 全部是 COMMAND，串行 | JavaDependency 是 READ，可并发 |

---

## 16.6 面试标准答法

**Q：你们为什么要专门实现 Java 工具，而不是直接用 Bash？**

> 三个核心问题：一、Bash 输出格式噪声太多，LLM 难以准确解析编译错误，导致频繁无效重试；JavaBuild 输出结构化的"N 个错误"摘要，首轮修复成功率显著提升。二、`./gradlew test` 全量运行可能需要几分钟；JUnitRun 支持 class/method 级精准过滤，自动处理 Maven（`-Dtest`）和 Gradle（`--tests`）格式差异，定向执行缩短到全量时间的 1/N。三、添加依赖前不知道是否已存在；JavaDependency 提供 list/tree/check-updates，查询时归类为 READ 工具可以并发调用，不阻塞其他操作。

---

*下一章：回顾与展望 — Harness/Context/Prompt 三层工程方法论*
