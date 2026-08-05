# 第10章：Slash Command — 内置命令系统

> 目标：理解 `/help`、`/metrics`、`/plan` 这类斜杠命令是如何实现的，以及三种命令类型的设计逻辑。
> 关键文件：`command/Command.java`、`command/CommandRegistry.java`、`command/CommandContext.java`

---

## 10.1 什么是 Slash Command？

用户在输入框里输入 `/help`、`/compact`、`/metrics`，这不是发给 LLM 的问题，而是给 Agent 框架本身的指令。这些指令叫做 **Slash Command（斜杠命令）**。

它们是 Agent 的"控制面板"——不需要消耗 LLM token，直接由框架处理。

CodePal 内置了这些命令：

| 命令 | 别名 | 作用 |
|------|------|------|
| `/help` | `h`, `?` | 显示帮助 |
| `/clear` | — | 清空对话，重新开始 |
| `/compact` | `c` | 手动触发上下文压缩 |
| `/status` | `s` | 显示当前状态（模式、token、工具数） |
| `/plan` | `p` | 切换到 Plan Mode |
| `/memory` | — | 管理自动记忆（list / clear） |
| `/metrics` | — | 显示 Agent 运行指标 |
| `/eval` | — | 运行评测用例 |
| `/skills` | — | 列出可用 Skill，支持热加载 |
| `/review` | — | 发起代码 review |
| `/session` | — | 会话管理（list / info） |
| `/sandbox` | — | 管理 OS 级沙箱模式 |
| `/resume` | `r` | 恢复历史会话 |
| `/rewind` | — | 回退到某个检查点 |

---

## 10.2 Command 数据结构

```java
// command/Command.java
public record Command(
    String name,          // 命令名，如 "help"（不含斜杠）
    String description,   // /help 里显示的描述
    String[] aliases,     // 别名，如 {"h", "?"}
    CommandType type,     // 分发类型
    boolean hidden        // 是否在 /help 中隐藏
) {
    public enum CommandType {
        LOCAL,     // 同步执行，返回文字输出
        LOCAL_UI,  // TUI 动作（切换模式、清屏），没有文字输出
        PROMPT     // 生成一段 prompt 发给 LLM
    }

    // 名字或别名匹配（大小写敏感）
    public boolean matches(String input) {
        if (name.equals(input)) return true;
        for (var alias : aliases) {
            if (alias.equals(input)) return true;
        }
        return false;
    }
}
```

**三种 CommandType 的区别，是整个设计的核心：**

**LOCAL**：命令由框架直接处理，同步返回文字结果，不涉及 LLM 和 TUI 状态变化。

例子：`/metrics` 调 `MetricsCommand.handle()` 返回统计数据字符串，直接显示给用户。

**LOCAL_UI**：命令触发 TUI 层的状态变化，没有文字返回值。

例子：`/clear` 需要清空聊天界面并重置 `ConversationManager`，这是 TUI 层的操作，光返回一个字符串没用。`/plan` 需要把 UI 切换到 Plan Mode，显示不同的 UI 状态。

**PROMPT**：命令把自己转化为一段 prompt，注入到对话里发给 LLM。

例子：`/review` 生成一段"请 review 当前 git diff，关注逻辑错误、安全问题..."的 prompt，然后让 LLM 去执行，就像用户自己输入了这段话一样。

---

## 10.3 CommandRegistry：注册与执行

```java
public class CommandRegistry {

    private final List<Command> commands = new ArrayList<>();
    private final Map<String, Function<CommandContext, String>> handlers = new HashMap<>();
    // 冲突检测索引
    private final Map<String, String> nameIndex = new HashMap<>();   // name → ownerName
    private final Map<String, String> aliasIndex = new HashMap<>();  // alias → ownerName

    public CommandRegistry() {
        registerDefaults();  // 构造器里直接注册所有内置命令
    }
```

**注册时的冲突检测**：

```java
public void register(Command cmd, Function<CommandContext, String> handler) {
    // 命令名不能和已注册命令名重复
    if (nameIndex.containsKey(cmd.name())) {
        throw new IllegalArgumentException("duplicate command name '%s'".formatted(cmd.name()));
    }
    // 命令名不能和已注册的别名冲突
    if (aliasIndex.containsKey(cmd.name())) {
        throw new IllegalArgumentException(
            "command name '%s' collides with alias of '%s'".formatted(cmd.name(), aliasIndex.get(cmd.name())));
    }
    // 每个别名都不能和已有命令名或别名冲突
    for (var alias : cmd.aliases()) {
        if (nameIndex.containsKey(alias) || aliasIndex.containsKey(alias)) {
            throw new IllegalArgumentException("alias conflict: " + alias);
        }
    }
    // ...注册
}
```

为什么要在注册时就检测冲突，而不是在执行时检测？

因为命令系统是在启动时一次性初始化的，注册时就报错能让开发者立刻发现配置问题，不会等到用户真正输入冲突的命令时才出错（那时候排查更困难）。这是**快速失败**（Fail Fast）原则的体现。

**handler 和 name/alias 的关系**：

```java
handlers.put(cmd.name(), handler);
for (var alias : cmd.aliases()) {
    handlers.put(alias, handler);  // 别名也指向同一个 handler
}
```

`/h` 和 `/help` 触发同一个 handler，不需要重复代码。

**执行流程**：

```java
public String execute(String name, CommandContext ctx) {
    // 先用名字/别名直接查
    Function<CommandContext, String> handler = handlers.get(name);
    if (handler != null) return handler.apply(ctx);

    // 再用 find 做模糊查找（支持别名）
    Optional<Command> cmd = find(name);
    if (cmd.isEmpty()) return "Unknown command: " + name;

    handler = handlers.get(cmd.get().name());
    if (handler != null) return handler.apply(ctx);

    return "No handler registered for /" + name;
}
```

---

## 10.4 CommandContext：依赖注入的精髓

所有 handler 都接收 `CommandContext` 参数，它不是把所有状态都直接暴露，而是通过 **Supplier / Runnable** 延迟提供：

```java
// command/CommandContext.java（概念结构）
public record CommandContext(
    String args,                                    // 命令参数（/memory list 的 "list"）
    String workDir,                                 // 工作目录
    String model,                                   // 当前模型名
    Supplier<String> permissionMode,                // 当前权限模式
    Supplier<int[]> tokenCount,                     // 当前 token 统计
    IntSupplier toolCount,                          // 工具数量
    Supplier<List<String>> memoryList,              // 记忆列表
    Runnable memoryClear,                           // 清除记忆的动作
    Supplier<String> mcpInfo,                       // MCP 服务器信息
    Supplier<String> sessionInfo,                   // 会话信息
    Supplier<List<String>> skillList,               // Skill 列表
    IntSupplier skillReload,                        // 热加载 Skill
    Supplier<String> sandboxStatus,                 // 沙箱状态
    Consumer<Integer> sandboxSwitch                 // 切换沙箱模式
) {}
```

**为什么用 Supplier 而不是直接传值？**

考虑 `/status` 命令，它需要当前 token 统计。但 token 统计是随着对话进行实时变化的，如果在创建 CommandContext 时就读取，拿到的是创建时的值，不是执行时的值。用 `Supplier<int[]>`，每次命令执行时才调用 `tokenCount.get()`，得到的是最新值。

这是**懒求值**（Lazy Evaluation）的应用：值的计算被延迟到真正需要它的时候。

**`/review` 是 PROMPT 类型的典型例子**：

```java
register(
    new Command("review", "Review current code changes",
            new String[]{}, CommandType.PROMPT, false),
    ctx -> {
        String prompt = "Please review the current git diff for code changes. Focus on:\n"
                + "1. Logic errors\n2. Security issues\n3. Performance problems\n4. Code style";
        if (ctx.args() != null && !ctx.args().isBlank()) {
            prompt += "\n\nAdditional focus: " + ctx.args().strip();
        }
        return prompt;  // 返回 prompt 字符串，TUI 会把它注入到对话里
    }
);
```

用户输入 `/review security`，`ctx.args()` 是 `"security"`，生成的 prompt 在末尾追加"Additional focus: security"，然后这段 prompt 作为用户消息发给 LLM，LLM 就执行一次带重点的 code review。整个过程对用户是透明的，感觉像 LLM 直接帮你做了 review。

---

## 10.5 命令补全（Tab 键自动完成）

```java
// 前缀搜索（用于 Tab 键自动完成）
public List<Command> search(String prefix) {
    String lower = prefix.toLowerCase(Locale.ROOT);
    return commands.stream()
            .filter(c -> !c.hidden())
            .filter(c -> c.name().toLowerCase().startsWith(lower)
                    || Arrays.stream(c.aliases()).anyMatch(a -> a.toLowerCase().startsWith(lower)))
            .sorted(Comparator.comparing(Command::name))
            .collect(Collectors.toList());
}
```

用户输入 `/me`，`search("me")` 返回 `[memory, metrics]`，TUI 显示补全选项。

---

## 10.6 面试官可能问的问题

**Q1：你们的 Slash Command 是怎么设计的？和 LLM 调用有什么区别？**

> Slash Command 是框架层的控制命令，不消耗 LLM token，直接由 CommandRegistry 处理。分三种类型：LOCAL 同步返回文字（如 `/metrics`），LOCAL_UI 触发 TUI 状态变化（如 `/clear`、`/plan`），PROMPT 生成 prompt 注入到对话发给 LLM（如 `/review`）。CommandContext 用 Supplier 延迟注入依赖，保证命令执行时读取最新状态。

**Q2：为什么要专门做 Slash Command？直接让 LLM 理解"帮我压缩上下文"这类自然语言指令不行吗？**

> 两个原因。一是**确定性**：`/compact`、`/clear` 这类控制操作必须 100% 可靠地触发，交给 LLM 理解自然语言有概率误判（用户说"清一下"，LLM 可能理解成清屏也可能理解成清空历史）。控制命令要走确定性代码路径，不能有歧义。二是**成本和延迟**：Slash Command 不消耗一个 LLM token、零延迟，而让 LLM 理解指令要发一整轮请求。像 `/status` 这种纯查询，走 LLM 是纯浪费。
>
> 分工原则：**需要模型智能判断的交给 LLM，确定性的控制操作交给命令系统**。这也是 Harness Engineering 的体现——不是所有事都要过 LLM。

**Q3：PROMPT 类型的命令和 Skill（第11章）有什么区别？两者都生成 prompt。**

> 相似点：都是把预设 prompt 注入对话发给 LLM。区别在于**复杂度和可扩展性**：
>
> - PROMPT 命令是**框架内置、代码写死**的（`/review` 的 prompt 就在 CommandRegistry 里硬编码），适合固定的、简单的、框架级的操作。
> - Skill 是**用户可定义的、文件驱动**的（放在 `.codepal/skills/` 下），支持工具过滤、fork 独立执行、热加载、三层覆盖。适合用户自定义的、复杂的、可复用的任务模板。
>
> 一句话：PROMPT 命令是"框架给你的快捷方式"，Skill 是"你自己定义的快捷方式"。想让某个 prompt 能被非开发者用户定制、能限制工具范围、能独立 fork 执行，就用 Skill；只是框架内几个固定操作，用 PROMPT 命令就够了。

**Q4：`/status` 为什么必须用 Supplier 延迟求值？直接传值会有什么 bug？**

> 因为 CommandContext 在**命令注册/上下文构建时**创建，而命令**执行时**才需要读值，两个时刻之间状态变了。举例：`/status` 要显示当前 token 数，如果构建 context 时就 `tokenCount = 1500` 传进去，那用户对话 10 轮后再敲 `/status`，显示的还是当初那个 1500——一个永远过期的数字。用 `Supplier<int[]>`，`tokenCount.get()` 在命令执行的那一刻才被调用，拿到的是实时值。凡是"随时间变化的状态"都必须走 Supplier，这是懒求值防止读到陈旧快照的经典应用。

---

## 10.7 生产中可能遇到的问题

**问题1：用户第一句话就以 `/` 开头，但不是命令**

真实用户可能输入 `/Users/foo/bar.txt 这个文件有 bug`（贴了个绝对路径），或者 `/etc/hosts 帮我看看`。框架不能把它当成 `/Users` 命令直接报 "Unknown command"，否则用户很困惑。

**处理策略**：`find(name)` 找不到匹配命令时，不应粗暴报错，而应判断——如果整个输入不像一个命令（含空格、含路径分隔符、长度过长），就当作普通用户消息发给 LLM。这是命令解析的第一个边界。

**问题2：命令参数解析的边界（引号、空格、特殊字符）**

`/review "focus on the auth module"` —— 引号内的空格应作为一个整体参数，还是按空格切分？`/memory clear all` —— `clear all` 是两个参数还是一个？框架当前把 `/` 后第一个 token 当命令名、剩余全部当 `args` 字符串（不做 shell 式分词），所以 `ctx.args()` 拿到的是原始剩余串。这个设计简单但要求命令 handler 自己解析 args——如果某个命令需要结构化参数，解析逻辑得自己写，容易出边界 bug（比如忘了 trim、没处理空 args）。

**问题3：LOCAL_UI 命令在非 TUI 模式下如何降级**

`/clear`、`/plan` 这类命令依赖 TUI 层做界面操作。但 CodePal 还有 Print 模式（`-p` 一次性输出）、Remote 模式（无交互界面）。在这些模式下执行 LOCAL_UI 命令，没有 UI 可操作。

**处理策略**：LOCAL_UI 命令的 handler 要能感知运行模式，在非 TUI 环境下降级为"打印一条提示"或直接忽略，而不是抛 NPE（TUI 组件为 null）。这是模式差异带来的隐蔽 bug 源。

**问题4：PROMPT 命令生成的 prompt 与用户后续输入混淆**

`/review` 生成的 prompt 被当作 user message 注入对话。如果用户紧接着又输入了内容，对话历史里会出现"两条连续的 user message"（review prompt + 用户的话），这违反了大多数 API 的 user/assistant 交替约束，可能导致 400。

**处理策略**：PROMPT 命令注入后应立即触发一轮 Agent 执行（消费掉这条 user message，产生 assistant 回复），再接受用户下一条输入；或者把用户后续输入合并进同一条 user message。

**问题5（用户视角）：用户记不住命令名、记不住别名**

内置十几个命令，用户记不全。缓解手段是三个：一是 Tab 补全（10.5 的 `search`）——输入 `/me` 提示 `memory`/`metrics`；二是别名（`/h` = `/help`）降低记忆负担；三是 `/help` 兜底。但 `/help` 本身如果命令太多会很长，需要按类别分组显示，否则用户在长列表里也找不到想要的。这是"功能多"和"可发现性"之间的经典体验权衡。

**问题6（用户视角）：用户敲错命令得到 "Unknown command" 却不知道正确的是什么**

用户想敲 `/metric` 但正确是 `/metrics`，得到 "Unknown command: metric" 就卡住了。更好的体验是做**模糊匹配提示**——"Unknown command: metric. Did you mean /metrics?"（用编辑距离找最接近的命令）。当前实现是精确匹配报错，这是可优化的用户体验点，面试被问"怎么优化命令系统的易用性"时可以答这个。

---

*下一章：Skill 系统 — 可复用的任务模板*
