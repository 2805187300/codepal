# 第6章：权限系统，给 Agent 装上"安全阀"

> 目标：理解为什么 Agent 需要权限控制，CodePal 五层权限架构的每一层是什么、解决什么问题，以及如何在不打扰用户的前提下保证安全。
> 对应简历亮点：第4条 — 五层安全隔离，权限弹窗从 30 次降至 5 次以内
> 关键文件：`permission/PermissionChecker.java`、`permission/PermissionMode.java`

---

## 6.1 为什么 Agent 需要权限控制？

先想一个场景：你让 Agent 帮你"清理一下项目里的临时文件"。

**没有权限控制的 Agent** 可能这样做：
1. 调 `Bash("find . -name '*.tmp' -delete")`，删掉了你以为的临时文件
2. 顺手又调 `Bash("rm -rf node_modules")`，说这也是可以删的
3. 又调 `Bash("git clean -fd")`，把你未提交的修改一并删掉了

最后你发现，三天没提交的代码消失了。

**根本原因**：Agent 有执行任意命令的能力，但没有边界。LLM 的"清理"理解和你的"清理"理解不一样，而且 LLM 有时候会"过度积极"地完成任务。

权限控制系统要解决的问题是：**让 Agent 有足够的能力完成任务，同时在它可能越权的时候及时拦截，并以最小的打扰代价做到这一点。**

最小打扰非常重要。如果每次调任何工具都弹窗确认，用户会在几分钟内就关掉 Agent——安全性拉满了，但可用性为零。

---

## 6.2 PermissionMode：四种运行档位

在深入五层架构之前，先理解四种宏观运行模式：

```java
// permission/PermissionMode.java

public enum PermissionMode {

    DEFAULT,      // 默认模式：读操作自动允许，写/命令需要确认
    ACCEPT_EDITS, // 接受编辑模式：读+写自动允许，命令需要确认
    PLAN,         // 计划模式：只读，不允许任何修改
    BYPASS;       // 旁路模式：全部自动允许（危险！）

    public Decision decide(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ    -> Decision.ALLOW;   // 读：自动放行
                case WRITE,
                     COMMAND -> Decision.ASK;     // 写/命令：需要确认
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ,
                     WRITE   -> Decision.ALLOW;   // 读+写：自动放行
                case COMMAND -> Decision.ASK;     // 命令：需要确认
            };
            case PLAN    -> DEFAULT.decide(category);  // 计划模式复用 DEFAULT 逻辑
            case BYPASS  -> Decision.ALLOW;            // 全部放行
        };
    }
}
```

**四种模式的使用场景：**

| 模式 | 使用场景 | 自动允许 | 需要确认 |
|------|---------|---------|---------|
| DEFAULT | 日常使用 | 读操作 | 写文件、执行命令 |
| ACCEPT_EDITS | 信任 Agent 修改文件时 | 读+写 | 执行命令 |
| PLAN | 先看方案再动手时 | — | 所有修改操作 |
| BYPASS | CI/CD 全自动场景 | 全部 | 无 |

**PLAN 模式复用 DEFAULT 的逻辑**是因为 Plan Mode 的额外限制在更高层实现（Layer 0），PermissionMode 层不需要重复。

---

## 6.3 五层权限架构全解

`PermissionChecker.check()` 是核心方法，一次工具调用进来，依次经过五层过滤：

```java
public CheckResult check(Tool tool, Map<String, Object> args) {
    String toolName = tool.name();
    String content = extractContent(toolName, args);  // 提取"关键内容"字段

    // Layer 0: Plan Mode 特殊处理
    // Layer 1: 安全命令自动放行
    // Layer 2: 危险命令直接拒绝
    // Layer 2b: 受保护路径拒绝写入
    // Layer 3: 路径沙箱检查
    // Layer 4: 文件规则匹配
    // Layer 4b: Session 级 allow-always 记忆
    // Layer 4c: OS 沙箱模式下命令放行
    // Layer 5: 模式矩阵（兜底）
}
```

下面逐层深挖。

---

### Layer 0：Plan Mode 特殊处理

```java
if (mode == PermissionMode.PLAN) {
    // Plan Mode 只允许这四个工具
    if (PLAN_MODE_ALLOWED_TOOLS.contains(toolName)) {
        return CheckResult.allow();
    }
    // 唯一例外：允许写 plan 文件
    if ("WriteFile".equals(toolName) || "EditFile".equals(toolName)) {
        String path = stringArg(args, "file_path", "");
        if (path.contains(".codepal/plans/")) {
            return CheckResult.allow();
        }
    }
}

private static final Set<String> PLAN_MODE_ALLOWED_TOOLS = Set.of(
    "Agent", "ToolSearch", "AskUserQuestion", "ExitPlanMode"
);
```

Plan Mode 的核心约束：**只能读，不能写**。Agent 可以用 ReadFile/Grep/Glob 探索代码，可以用 Agent 工具启动子 Agent，但不能修改任何文件，也不能执行任何命令。

唯一的例外是 `.codepal/plans/` 目录——Agent 把生成的方案写到 plan 文件里，供用户审批，这个写入是被允许的。

**为什么 Plan Mode 需要单独一层而不是用 Layer 5 的模式矩阵？**

因为 Plan Mode 的逻辑比"按工具类别决定"更复杂：
1. Agent/ToolSearch/AskUserQuestion/ExitPlanMode 这几个工具是 Plan Mode 必须用的（否则无法工作），要无条件放行
2. 写 plan 文件是 Plan Mode 唯一被允许的写入，需要路径级别的精确判断

---

### Layer 1：安全命令自动放行

```java
if ("Bash".equals(toolName) && content != null && isSafeCommand(content)) {
    return CheckResult.allow();
}

private boolean isSafeCommand(String command) {
    String trimmed = command.trim();
    // 如果命令包含管道、分号、重定向、命令替换，不算安全
    if (trimmed.contains("|") || trimmed.contains(";") || trimmed.contains("&&")
            || trimmed.contains(">") || trimmed.contains("$(") || trimmed.contains("`")) {
        return false;
    }
    // 白名单匹配
    for (var safe : SAFE_COMMANDS) {
        if (trimmed.equals(safe) || trimmed.startsWith(safe + " ")) {
            return true;
        }
    }
    return false;
}

private static final Set<String> SAFE_COMMANDS = Set.of(
    "ls", "pwd", "cat", "grep", "git status", "git log", "git diff",
    "git branch", "find", "sort", "java -version", ...
);
```

**设计核心：白名单 + 复合命令排除**

`SAFE_COMMANDS` 是一个经过精心筛选的命令白名单：纯读操作（ls、cat、grep）、git 查看命令（git status、git log）、版本检查（java -version）等，这些命令没有任何副作用，一定是安全的。

关键是 `isSafeCommand` 里的**复合命令检测**：

```java
if (trimmed.contains("|") || trimmed.contains(";") || trimmed.contains("&&")
        || trimmed.contains(">") || trimmed.contains("$(") || trimmed.contains("`")) {
    return false;
}
```

即使 `grep` 在白名单里，`grep xxx | rm -rf .` 也不安全。复合命令中任何一段可能有副作用，所以只要命令包含拼接操作符，整条就不算安全，必须走后续层次检查。这防止了通过命令拼接绕过白名单的攻击。

---

### Layer 2：危险命令直接拒绝

```java
if ("Bash".equals(toolName) && content != null) {
    for (var pattern : DANGEROUS_PATTERNS) {
        if (pattern.matcher(content).find()) {
            return CheckResult.deny("Dangerous command detected: " + pattern.pattern());
        }
    }
}

private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
    Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$"),  // rm -rf /
    Pattern.compile("mkfs\\."),                                   // 格式化磁盘
    Pattern.compile("dd\\s+if=.*of=/dev/"),                       // 直写磁盘
    Pattern.compile("chmod\\s+-R\\s+777\\s+/"),                   // 全局 777
    Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),             // fork bomb
    Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),                  // curl | bash
    Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),                  // wget | bash
    Pattern.compile(">\\s*/dev/sd")                               // 写入磁盘设备
);
```

**逐条分析这些危险模式：**

1. `rm -rf /` → 删除整个根目录，毁灭性操作
2. `mkfs.xxx` → 格式化磁盘分区，不可恢复
3. `dd if=xxx of=/dev/xxx` → 直接写入磁盘设备，可以覆盖整个磁盘
4. `chmod -R 777 /` → 把整个系统的权限改为全可写，严重安全漏洞
5. `:(){ :|:& };:` → Fork Bomb，无限创建进程，耗尽系统资源（用正则匹配其典型形式）
6. `curl xxx | bash` / `wget xxx | bash` → 从网络下载脚本直接执行，供应链攻击的常见手段
7. `> /dev/sda` → 写入磁盘设备，覆盖整个磁盘

这些命令在正常编程任务中**几乎不会出现**，但一旦出现，后果可能是不可恢复的。所以直接 `DENY`，不询问用户，不给 LLM 任何机会执行。

**值得注意**：这一层只负责"明显危险"的命令。对于"可能危险"的命令（比如 `git push --force`），放到后续层次去判断。

---

### Layer 2b：受保护路径禁止写入

```java
if (content != null && isWritePathTool(toolName) && isDeniedPath(content)) {
    return CheckResult.deny("Path is protected by sandbox: " + content);
}

private static final List<String> DEFAULT_DENY_WRITE = List.of(
    ".codepal/config.yaml",           // 配置文件
    ".codepal/permissions.local.yaml", // 权限规则文件
    ".codepal/skills/"                 // Skill 文件目录
);
```

**为什么要专门保护这三个路径？**

如果 AI 能修改这些文件，它就能：
- 修改 `config.yaml`：把自己的 API Key 换掉，或者修改模型配置
- 修改 `permissions.local.yaml`：给自己授予更多权限，绕过规则
- 修改 `skills/`：植入恶意 Skill，影响后续所有操作

这是典型的**权限提升攻击**（Privilege Escalation）。即使用户授权了"可以写任何文件"，这三个路径也始终是禁区。

`isDeniedPath` 用**绝对路径前缀匹配**，防止路径遍历（`../../.codepal/config.yaml` 这样的绕过）：

```java
private boolean isDeniedPath(String pathStr) {
    String normalized = Path.of(pathStr).toAbsolutePath().normalize().toString();
    for (String deny : denyWrite) {
        if (normalized.startsWith(deny)) {
            return true;
        }
    }
}
```

---

### Layer 3：路径沙箱

```java
if (content != null && isPathTool(toolName)) {
    if (!isPathAllowed(content) && mode != PermissionMode.BYPASS) {
        return CheckResult.ask("Path outside allowed sandbox: " + content);
    }
}

private boolean isPathAllowed(String pathStr) {
    Path p    = Path.of(pathStr).toAbsolutePath().normalize();
    Path root = projectRoot.toAbsolutePath().normalize();
    Path tmp  = Path.of("/tmp").toAbsolutePath().normalize();
    return p.startsWith(root) || p.startsWith(tmp);
}
```

**路径沙箱的边界**：文件操作只能在**项目根目录**或 `/tmp` 下进行。

如果 LLM 试图读 `/etc/passwd` 或者写 `/usr/local/bin/`，就会被拦截并弹窗询问用户。

**`p.startsWith(root)` 的正确性**：这里用的是 `java.nio.Path`，它做的是路径组件级别的前缀匹配，而不是字符串匹配。所以 `/home/user/projects-evil` 不会匹配 `/home/user/project`（尾部加了 `-evil`）。纯字符串的 `startsWith` 会有这个漏洞，而 `Path.startsWith` 没有。

---

### Layer 4：文件规则匹配（YAML 配置）

```java
for (int i = fileRules.size() - 1; i >= 0; i--) {  // 从最后往前，最后一条规则优先
    PermissionRule rule = fileRules.get(i);
    if (rule.matches(toolName, content)) {
        return switch (rule.effect) {
            case ALLOW -> CheckResult.allow();
            case DENY  -> CheckResult.deny("Denied by rule: ...");
            case ASK   -> CheckResult.ask();
        };
    }
}
```

这一层允许用户通过 YAML 文件自定义规则，格式如下：

```yaml
# .codepal/permissions.yaml（项目级，提交 git）
- rule: "Bash(git *)"
  effect: allow            # git 命令全部自动放行

- rule: "WriteFile(/etc/*)"
  effect: deny             # 禁止写 /etc/ 下的文件

# .codepal/permissions.local.yaml（用户级，gitignore）
- rule: "Bash(npm run *)"
  effect: allow            # 允许 npm run 命令（当前用户专属规则）
```

**规则来自三个层级，按优先级叠加：**

```java
private List<PermissionRule> loadRules() {
    var rules = new ArrayList<PermissionRule>();
    // 用户级：~/.codepal/permissions.yaml
    rules.addAll(loadRulesFile(userFile));
    // 项目级：{projectRoot}/.codepal/permissions.yaml
    rules.addAll(loadRulesFile(projectFile));
    // 本地级：{projectRoot}/.codepal/permissions.local.yaml（gitignore）
    rules.addAll(loadRulesFile(localFile));
    return rules;
}
```

**"最后一条规则优先"（Last Wins）的设计原因：**

这和 CSS 的规则一样——越靠后的规则优先级越高。用户级规则加载最早（最前），本地级规则加载最晚（最后）。所以本地级可以覆盖项目级可以覆盖用户级，这符合"越具体越优先"的直觉。

**通配符匹配的实现：**

```java
private static boolean globMatch(String pattern, String content) {
    // 把 glob 模式转成正则：* 变成 .*，? 变成 .
    // 其他特殊字符转义
    String re = "^" + pattern
            .replace("*", ".*")
            .replace("?", ".")
            // ... 其他字符转义
            + "$";
    return content.matches(re);
}
```

`Bash(git *)` 的 `*` 匹配"任意字符"，所以 `git status`、`git log`、`git commit -m "xxx"` 都能匹配。

---

### Layer 4b：Session 级 allow-always 记忆

```java
if (allowAlwaysRules.contains(toolName + ":" + content)) {
    return CheckResult.allow();
}

// 当用户选择 "Allow Always" 时，记录下来
public void addAllowAlwaysRule(String toolName, String content) {
    allowAlwaysRules.add(toolName + ":" + content);
}
```

这一层对应用户在权限弹窗里选择"允许，以后不再询问"的情况。

**这是弹窗次数从 30 次降到 5 次的核心机制。**

想象一下：用户开始一个复杂的重构任务，第一次调 `Bash("./gradlew build")` 弹窗了，用户点"允许，以后不再询问"。之后这个任务里所有 `./gradlew build` 的调用都直接放行，不再弹窗。

Session 级意味着：这个记忆在当次会话内有效，重启后清空。如果想跨会话持久化，应该把规则写到 `permissions.local.yaml` 文件里。

**Key 的格式 `toolName:content`**：

```java
allowAlwaysRules.add(toolName + ":" + content);
```

注意这是**精确匹配**，不是通配符匹配。`Bash:./gradlew build` 只记忆这一条具体命令，不会把所有 `gradlew` 命令都放行。安全边界更清晰。

---

### Layer 4c：OS 沙箱模式下命令放行

```java
if (sandboxEnabled && tool.category() == ToolCategory.COMMAND) {
    // 拆分复合命令逐条检查显式 deny/ask 规则
    String[] subcommands = content.split("\\s*(?:&&|\\|\\||[;|])\\s*");
    boolean hasAsk = false;
    for (String sub : subcommands) {
        for (int i = fileRules.size() - 1; i >= 0; i--) {
            PermissionRule rule = fileRules.get(i);
            if (rule.matches(toolName, sub)) {
                if (rule.effect == RuleEffect.DENY) return CheckResult.deny(...);
                if (rule.effect == RuleEffect.ASK) hasAsk = true;
                break;
            }
        }
    }
    if (hasAsk) return CheckResult.ask();
    return CheckResult.allow();  // 否则放行，OS 沙箱兜底
}
```

当用户启用了 OS 级沙箱（macOS seatbelt 或 Linux bwrap），命令执行已经在内核级别受到限制，软件层面不需要再询问每一条命令。

**但仍然检查显式规则**：即使有 OS 沙箱，`permissions.yaml` 里写了 deny 的规则依然生效，防止通过命令拼接绕过显式禁令。

这个设计体现了"纵深防御"（Defense in Depth）：软件层权限 + OS 层沙箱，两层各司其职，互相补充。

---

### Layer 5：模式矩阵（最终兜底）

```java
var decision = mode.decide(tool.category());
return switch (decision) {
    case ALLOW -> CheckResult.allow();
    case DENY  -> CheckResult.deny("Denied by permission mode: " + mode);
    case ASK   -> CheckResult.ask();
};
```

前面所有层都没有匹配时，最终由 `PermissionMode.decide()` 兜底。这就是 6.2 节的模式矩阵：DEFAULT 模式下，READ 工具放行，WRITE/COMMAND 工具弹窗。

---

## 6.4 五层架构的完整决策流程图

```
工具调用进来
      │
      ▼
[Layer 0] Plan Mode？
      ├─ 是 + 工具在白名单（Agent/ToolSearch/...） → ALLOW
      ├─ 是 + 写 plan 文件 → ALLOW
      └─ 是 + 其他 → 继续（后续层会 ASK 或 DENY）
      │
      ▼
[Layer 1] Bash 命令 + 在安全白名单？
      ├─ 是（且不含复合操作符）→ ALLOW（快速放行，减少弹窗）
      └─ 否 → 继续
      │
      ▼
[Layer 2] Bash 命令 + 匹配危险模式？
      ├─ 是 → DENY（直接拒绝，不询问）
      └─ 否 → 继续
      │
      ▼
[Layer 2b] 写操作 + 路径在受保护列表？
      ├─ 是 → DENY（永久禁区，防权限提升）
      └─ 否 → 继续
      │
      ▼
[Layer 3] 文件操作 + 路径在沙箱之外？
      ├─ 是（且不是 BYPASS 模式）→ ASK
      └─ 否 → 继续
      │
      ▼
[Layer 4] 匹配文件规则（YAML）？
      ├─ 匹配到 ALLOW 规则 → ALLOW
      ├─ 匹配到 DENY 规则 → DENY
      ├─ 匹配到 ASK 规则 → ASK
      └─ 未匹配 → 继续
      │
      ▼
[Layer 4b] 在 allow-always 记忆中？
      ├─ 是 → ALLOW（用户之前说过"不再询问"）
      └─ 否 → 继续
      │
      ▼
[Layer 4c] OS 沙箱已启用 + 命令类工具？
      ├─ 是（且无显式 deny/ask 规则）→ ALLOW（OS 已兜底）
      └─ 否 → 继续
      │
      ▼
[Layer 5] 模式矩阵兜底
      READ → ALLOW
      WRITE/COMMAND（DEFAULT 模式）→ ASK
```

---

## 6.5 权限弹窗的异步交互：CompletableFuture

当某一层返回 `ASK`，`StreamingExecutor.executeSingle` 里的处理：

```java
case ASK -> {
    // 1. 创建一个"等待用户答复"的 Future
    var future = new CompletableFuture<PermissionResponse>();

    // 2. 生成人类可读的描述："Edit: /src/Agent.java"
    String desc = checker.describeToolAction(call.toolName(), call.args());

    // 3. 把事件（含 future）推进队列，TUI 会消费它弹窗
    putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));

    // 4. Agent 线程在这里阻塞等待，最多等 5 分钟
    PermissionResponse response;
    try {
        response = future.get(5, TimeUnit.MINUTES);
    } catch (TimeoutException e) {
        response = PermissionResponse.DENY;  // 超时视为拒绝
    }

    // 5. 根据用户的选择处理
    if (response == PermissionResponse.DENY) {
        return error("Permission denied by user");
    }
    if (response == PermissionResponse.ALLOW_ALWAYS) {
        // 用户选了"以后不再询问"，把规则记入 allow-always
        String content = extractContent(call.toolName(), call.args());
        checker.addAllowAlwaysRule(call.toolName(), content);
    }
    // ALLOW：继续执行
}
```

**TUI 线程的处理（对侧）：**

```java
// 消费 PermissionRequestEvent
case AgentEvent.PermissionRequestEvent e -> {
    // 显示权限确认对话框
    showPermissionDialog(e.toolName(), e.description(), userChoice -> {
        // 用户点击后，complete future
        e.future().complete(userChoice);
    });
}
```

**整个过程完全异步**：Agent 线程阻塞在 `future.get()`，TUI 线程正常运行（响应用户输入），用户做出选择后 `future.complete()` 解除阻塞。两个线程没有任何直接耦合，只通过 `CompletableFuture` 通信。

---

## 6.6 面试官可能问的问题

**Q：你的权限系统是怎么设计的？为什么叫"五层"？**

> 五层顺序执行，从"最明确"到"最通用"：Layer 0 处理 Plan Mode 特殊逻辑；Layer 1 白名单快速放行安全命令，减少不必要弹窗；Layer 2 正则匹配直接拒绝明确危险命令；Layer 3 路径沙箱限制文件操作范围；Layer 4 用户可配置的 YAML 规则加上 Session 级 allow-always 记忆；Layer 5 模式矩阵兜底。每层只处理自己负责的范围，通过的才到下一层。这样既精准（不误杀合法操作），又全面（没有绕过路径）。

**Q：弹窗次数是怎么从 30 次降到 5 次以内的？**

> 三个机制协同作用：第一，Layer 1 的安全命令白名单覆盖了大量只读和常用命令（ls、git status、cat、grep 等），这些命令直接放行，不弹窗；第二，Layer 4b 的 allow-always 记忆，用户选过一次"以后不再询问"的命令，整个 Session 内不再弹窗；第三，合理的 PermissionMode 选择——大多数项目用 ACCEPT_EDITS 模式，读写操作自动放行，只有执行命令才弹窗。三层一起，绝大多数重复性操作都被自动放行。

**Q：如何防止 LLM 通过修改配置文件来提升自己的权限？**

> Layer 2b 的受保护路径机制。`config.yaml`、`permissions.local.yaml`、`skills/` 这三个路径是永久禁区，即使用户授权了"可以写任何文件"，这些路径也始终拒绝写入。实现上用绝对路径前缀匹配（`Path.startsWith`，不是字符串 startsWith），防止路径遍历攻击。这保证了权限系统本身不能被 AI 篡改，是整个安全架构的信任锚点。

**Q（追问）：先补个概念——白名单和黑名单的区别是什么？你的系统哪些层是白、哪些是黑？**

> 这是安全设计的根本哲学差异：
> - **白名单（allowlist）**：默认拒绝，只放行明确列出的。安全上限高（没列的一律不许），但要维护"允许清单"。
> - **黑名单（denylist）**：默认允许，只拦截明确列出的。灵活，但**永远列不全**——总有你没想到的危险变体。
>
> CodePal 里两者都有：Layer 1 安全命令白名单（列出的才快速放行）、Layer 2 危险命令黑名单（列出的直接拒绝）。关键判断：**安全兜底不能只靠黑名单**。Layer 2 的危险命令正则是黑名单，它挡得住 `rm -rf /`，但挡不住所有变体。真正的兜底是 Layer 3 路径沙箱（白名单式：只准在项目内和 /tmp 操作）和 OS 沙箱（内核级白名单）。黑名单负责"快速拦掉最明显的"，白名单和沙箱负责"兜住没想到的"。

**Q（追问）：你说"五层"，但流程图里我数出 Layer 0/1/2/2b/3/4/4b/4c/5 有 9 个判断点，到底几层？**

> "五层"是**逻辑分层**，指五类不同性质的防御：①模式特殊处理（Layer 0）②命令白/黑名单（Layer 1/2）③路径保护与沙箱（Layer 2b/3）④用户规则与记忆（Layer 4/4b/4c）⑤模式矩阵兜底（Layer 5）。带字母后缀的（2b/4b/4c）是同一逻辑层内的细分判断点，不是独立的层。面试如实说清楚这个"逻辑五层、实现九个判断点"的关系就行——被数出来质疑时能解释，比含糊其辞好。

**Q（追问）：你的危险命令黑名单能被绕过吗？**

> 能，黑名单理论上永远能被绕过。比如 `rm -rf /` 有变体 `rm -rf /*`、`rm -rf ~`、`rm --recursive --force /`、或者写个脚本 `sh -c 'rm -rf /'` 间接执行——正则很难穷举所有形式。所以我**不把黑名单当作完备防护**，它只是"尽力而为"的第一道快速拦截。真正的安全边界是路径沙箱（限制操作范围）和 OS 级沙箱（seatbelt/bwrap，内核级隔离）——就算命令绕过了黑名单、真的执行了，它能触及的文件系统范围也被沙箱死死限制住。诚实承认黑名单的局限、并说明有沙箱兜底，比吹嘘"我的正则能挡住一切"更能体现安全素养。

---

## 6.7 生产中可能遇到的问题

**问题1：弹窗频率还是太高，用户体验差**

根本原因：工具调用模式中有太多"第一次见到的命令"，还没有 allow-always 记忆。

解法：预先在 `permissions.yaml` 里配置项目常用的放行规则：
```yaml
- rule: "Bash(./gradlew *)"
  effect: allow
- rule: "Bash(npm run *)"
  effect: allow
- rule: "Bash(docker *)"
  effect: ask
```

这样常见的构建命令直接放行，只有真正敏感的操作才弹窗。

**问题2：路径沙箱太严格，Agent 无法读取项目外的依赖**

比如读取 `~/.m2/repository` 下的 Maven 依赖源码，路径在项目目录之外，被 Layer 3 拦截。

解法：用 `permissions.yaml` 在 Layer 4 添加允许规则，或者用 BYPASS 模式（但要评估风险）。更好的做法是在 YAML 里精确允许特定路径：
```yaml
- rule: "ReadFile(/Users/xxx/.m2/*)"
  effect: allow
```

**问题3：allow-always 记忆在进程重启后消失**

`allowAlwaysRules` 是内存里的 `HashSet`，进程重启就清空。对于"真的想持久化的规则"，需要用户手动添加到 `permissions.local.yaml`。

CodePal 的 `appendLocalRule` 方法提供了这个能力：

```java
public void appendLocalRule(String toolName, String pattern) {
    // 追加到 .codepal/permissions.local.yaml
    // 并立即 reload fileRules
}
```

TUI 可以在用户点"永久允许"时调用这个方法，把规则写进文件。但目前 CodePal 的实现里这个 UI 流程还没完全打通，是一个可以改进的点。

**问题4：正则匹配危险命令有误判**

比如 `rm -rf node_modules`（合法）会匹配到危险模式 `rm -rf /` 吗？

看正则：`Pattern.compile("rm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+/\\s*$")`

关键是末尾的 `\\s+/\\s*$`：要求 `rm -rf` 之后的参数是 `/`（根目录），可以有前后空白，且是字符串结尾。`rm -rf node_modules` 的参数是 `node_modules`，不匹配。

但如果有人写 `rm -rf /tmp/node_modules`，这里有 `/`，匹配到了吗？`\\s+/` 要求 `-rf` 后面直接跟一个空格加斜杠，`/tmp/node_modules` 的 `/` 前面没有单独的空格，不匹配。

不过这种正则边界情况在实际使用中可能还有遗漏，生产系统应该做更完善的测试覆盖。

**问题5（用户视角）：用户误点了"永久允许"一个危险操作，怎么撤销？**

用户手快，在弹窗里对一条其实不该放行的命令点了"以后不再询问"，之后它就一路畅通了。用户想反悔却找不到入口。

解法：`/permissions` 或类似命令要能**列出当前会话的 allow-always 记忆并支持撤销**；持久化到 `permissions.local.yaml` 的规则，用户能直接编辑文件删除。权限的"授予"必须配套"可查看、可撤销"——只能加不能减的权限系统是危险的。

**问题6（用户视角）：BYPASS 模式忘了关，酿成事故**

用户为了跑一个批量任务临时开了 BYPASS（全部自动放行），任务结束忘了切回来，下一个任务里 Agent 的危险操作也被静默放行了。

解法：一是 BYPASS 模式在 UI 上要有**持续的醒目提示**（比如状态栏常驻红色"⚠ BYPASS 模式"），时刻提醒用户当前无防护；二是可考虑 BYPASS 只对单次任务生效、任务结束自动复位。危险模式的"退出机制"和"进入机制"一样重要。

**问题7（用户视角）：权限弹窗 5 分钟超时自动 DENY，用户回来发现任务停了却不知为何**

用户发起任务后去接了个电话，回来发现 Agent 卡在某处没动——其实是权限弹窗等了 5 分钟超时被自动拒绝，任务中断了，但用户没看到当时的弹窗。

解法：超时 DENY 后要留下**明确的痕迹**（"权限请求超时（等待 5 分钟无响应），已自动拒绝并中断，可重新发起"），而不是让任务悄无声息地停住。让用户能理解"为什么停了、怎么恢复"，是长任务 + 人工确认这种交互模式必须处理好的体验缺口。

---

*下一章：MCP 协议 — 开放式工具生态的实现*
