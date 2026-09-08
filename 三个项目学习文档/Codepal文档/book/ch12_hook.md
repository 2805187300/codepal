# 第12章：Hook 系统 — 生命周期钩子与自动化

> 目标：理解 Hook 是什么、9 种事件和 4 种动作类型、条件表达式如何工作，以及 Hook 在 Agent 工作流自动化里的实际应用。
> 关键文件：`hook/HookEngine.java`

---

## 12.1 什么是 Hook？

Hook（钩子）是 Agent 生命周期里的"埋点"：在特定事件发生时，自动触发用户预设的动作。

举几个实际场景：

- **每次 Agent 调用 EditFile 之前，自动运行 lint 检查**
- **Agent 修改了 `.sql` 文件时，发一条 Slack 通知**
- **每轮 Agent Loop 结束后，把对话记录写到日志文件**
- **Agent 修改了 `package.json`，自动运行 `npm install`**

没有 Hook，这些自动化逻辑要么写死在框架里，要么靠用户手动处理。有了 Hook，用户通过配置文件就能定制这些行为，不需要修改 Agent 代码。

---

## 12.2 9 种事件：覆盖 Agent 全生命周期

```java
public enum EventName {
    SESSION_START("session_start"),   // 新会话开始时
    SESSION_END("session_end"),       // 会话结束时
    TURN_START("turn_start"),         // 每轮 Agent Loop 开始前
    TURN_END("turn_end"),             // 每轮 Agent Loop 结束后
    PRE_SEND("pre_send"),             // 发送给 LLM 之前
    POST_RECEIVE("post_receive"),     // 收到 LLM 响应之后
    PRE_TOOL_USE("pre_tool_use"),     // 工具执行之前 ← 最常用
    POST_TOOL_USE("post_tool_use"),   // 工具执行之后 ← 最常用
    SHUTDOWN("shutdown")              // Agent 关闭时
}
```

其中 `PRE_TOOL_USE` 和 `POST_TOOL_USE` 最常用，前者可以拦截工具调用，后者可以做清理或通知。

---

## 12.3 4 种动作类型

```java
public enum ActionType {
    COMMAND("command"),  // 执行 bash 命令
    PROMPT("prompt"),    // 把一段文字注入到对话
    HTTP("http"),        // 发 HTTP 请求（Webhook）
    AGENT("agent")       // 启动子 Agent
}
```

**COMMAND 动作：** 执行 bash 命令，自动注入环境变量：

```bash
# Hook 执行命令时能拿到这些环境变量
CODEPAL_EVENT=pre_tool_use
CODEPAL_TOOL=EditFile
CODEPAL_FILE_PATH=/src/Agent.java
```

用这些环境变量，你的 bash 脚本就能知道触发 Hook 的上下文。

**PROMPT 动作：** 把指定内容注入到 LLM 的下一轮上下文。比如"你刚刚修改了 security 相关文件，请在继续之前检查权限控制是否完整"。

**HTTP 动作：** 发 Webhook 通知，适合接入 Slack、企业微信、PagerDuty 等告警系统。没有指定 body 时，自动生成包含事件上下文的 JSON。

**AGENT 动作：** 最强大的动作类型。用于"修改了某个文件之后，让另一个 Agent 做验证"这类场景。它不是执行一条命令或发个通知，而是**启动一个完整的子 Agent**（复用第13章的 SubAgent 机制），让子 Agent 带着一段指令自主完成一个多步骤任务。

完整配置示例——"改了 schema.sql 后，自动 fork 一个 Agent 生成对应的迁移脚本"：

```yaml
hooks:
  - id: auto-migration
    event: post_tool_use
    condition: 'tool == "WriteFile" && args.file_path =~ /schema\.sql$/'
    action:
      type: agent
      prompt: |
        用户刚修改了 ${args.file_path}。
        请对比 schema 的变更，在 migrations/ 目录下生成一个新的迁移脚本，
        文件名用当前时间戳前缀。只生成迁移脚本，不要改其他文件。
      model: claude-opus-4-8
    async: true    # 迁移脚本生成不阻塞主 Agent 继续工作
```

执行时 HookEngine 大致这样驱动子 Agent（复用 fork 机制）：

```java
// executeAction 里 AGENT 分支（示意）
case AGENT -> {
    String prompt = ctx.expand(action.prompt());   // 先做 ${var} 模板替换
    Agent sub = new Agent(client, registry, protocol, cfg);
    sub.setMaxIterations(15);                        // 限制轮数，防止跑偏
    ConversationManager subConv = new ConversationManager();
    subConv.addUserMessage(prompt);
    // 驱动到 LoopComplete，收集最终输出作为 HookResult
    BlockingQueue<AgentEvent> q = sub.run(subConv);
    // ... drain 事件直到 LoopComplete
}
```

**为什么 AGENT 动作"最强大"也"最危险"**：它能让 Hook 触发任意复杂的自主行为。强大之处是自动化程度极高（一个文件变更能触发一整套后续处理）；危险之处是子 Agent 有自己的工具和权限，如果 prompt 写得含糊，可能做出意料之外的修改。所以 AGENT 动作通常配 `async: true`（不阻塞主流程）+ 明确的 maxIterations 限制 + 收窄的工具过滤。

---

## 12.4 Hook 配置结构

一个 Hook 的完整定义：

```java
public record Hook(
    String id,           // Hook 的唯一 ID（用于 once 去重）
    EventName event,     // 触发事件
    String condition,    // 触发条件（可选）
    Action action,       // 要执行的动作
    boolean reject,      // 是否拒绝（仅 pre_tool_use）
    boolean once,        // 是否只触发一次
    boolean async,       // 是否异步执行（不阻塞主流程）
    String onError       // 错误策略："reject" 或 null
) {}
```

**配置文件示例（`.codepal/config.yaml`）**：

```yaml
hooks:
  # Hook 1：每次 EditFile 之前，如果是 .java 文件，运行 checkstyle
  - id: java-lint
    event: pre_tool_use
    condition: 'tool == "EditFile" && args.file_path =* "*.java"'
    action:
      type: command
      command: "./gradlew checkstyleMain -q"
      timeout: 30s
    on_error: reject    # checkstyle 失败就拒绝这次 EditFile

  # Hook 2：修改了数据库迁移文件时，Webhook 通知
  - id: db-migration-alert
    event: post_tool_use
    condition: 'tool == "WriteFile" && args.file_path =~ /migrations\/.+\.sql/'
    action:
      type: http
      url: "https://hooks.slack.com/services/xxx"
      body: '{"text":"CodePal 修改了数据库迁移文件：${args.file_path}"}'
    async: true          # 异步发送，不阻塞 Agent

  # Hook 3：每次会话结束时，把 metrics 写入日志
  - id: session-metrics
    event: session_end
    action:
      type: command
      command: 'echo "$(date): session ended" >> ~/.codepal/sessions.log'
    once: false
```

---

## 12.5 条件表达式：灵活的触发控制

条件表达式支持四种操作符和布尔组合：

```
== 精确相等        tool == "EditFile"
!= 不等           tool != "Bash"
=~ 正则匹配       args.file_path =~ /\.sql$/
=* glob 匹配      args.file_path =* "src/**/*.java"
&& 逻辑与         tool == "EditFile" && args.file_path =* "*.java"
|| 逻辑或         tool == "WriteFile" || tool == "EditFile"
!  取反           !tool == "ReadFile"
```

**解析实现：递归下降**

```java
static boolean evaluateCondition(String condition, HookContext ctx) {
    String cond = condition.strip();

    // 1. 尝试拆分复合条件（&& 和 ||）
    List<CompToken> tokens = splitComposite(cond);
    if (tokens != null && tokens.size() > 1) {
        boolean result = evaluateCondition(tokens.get(0).expr, ctx);
        for (int i = 1; i < tokens.size(); i++) {
            boolean rhs = evaluateCondition(tokens.get(i).expr, ctx);
            result = "&&".equals(tokens.get(i).op) ? result && rhs : result || rhs;
        }
        return result;
    }

    // 2. 取反
    if (cond.startsWith("!")) return !evaluateCondition(cond.substring(1).strip(), ctx);

    // 3. 叶子条件：var op value
    return evaluateLeaf(cond, ctx);
}
```

**叶子条件求值**：

```java
static boolean evaluateLeaf(String condition, HookContext ctx) {
    for (String op : new String[]{"!=", "=~", "=*", "=="}) {  // 注意 != 必须在 == 前检查
        int idx = condition.indexOf(op);
        if (idx >= 0) {
            String left = condition.substring(0, idx).strip();
            String right = stripQuotes(condition.substring(idx + op.length()).strip());
            String val = resolveVar(left, ctx);

            return switch (op) {
                case "==" -> val.equals(right);
                case "!=" -> !val.equals(right);
                case "=~" -> Pattern.compile(stripSlashes(right)).matcher(val).find();
                // find() 而不是 matches() — 部分匹配，和 Go 的 regexp.MatchString 语义一致
                case "=*" -> FileSystems.getDefault()
                                .getPathMatcher("glob:" + right)
                                .matches(Paths.get(val));
                default -> false;
            };
        }
    }
    // 没有操作符 → 变量非空即为 true
    return !resolveVar(condition.strip(), ctx).isEmpty();
}
```

**支持的变量**：

```java
static String resolveVar(String name, HookContext ctx) {
    return switch (name) {
        case "tool"      -> ctx.toolName();      // 当前工具名
        case "event"     -> ctx.event().value(); // 事件名
        case "file_path" -> ctx.filePath();      // 文件路径（如果有）
        case "message"   -> ctx.message();       // 消息内容
        default -> {
            if (name.startsWith("args.")) {      // 工具参数：args.file_path, args.command...
                String key = name.substring("args.".length());
                yield ctx.toolArgs().get(key).toString();
            }
            yield "";
        }
    };
}
```

**注意 `=~` 用 `find()` 不用 `matches()`**：这个细节很重要。`matches()` 要求整个字符串匹配正则，而 `find()` 是部分匹配（只要有子串匹配就返回 true）。用 `find()` 与 Go 版行为一致，用起来也更直觉：`tool =~ /Edit/` 匹配任何包含"Edit"的工具名。

**条件表达式的健壮性：用户写错了会怎样？**

这个解析器面对的是**用户手写的配置**，用户很容易写错。几个必须考虑的边界：

1. **未知变量**：`resolveVar` 的 `default` 分支对未识别的变量返回 `""`（空字符串），不抛异常。所以 `foo == "bar"` 里的 `foo` 解析成空串、比较为 false，Hook 静默不触发——不会炸，但用户可能纳闷"为什么没生效"。

2. **`args.xxx` 键不存在会 NPE**：看 `resolveVar` 里 `ctx.toolArgs().get(key).toString()` ——如果工具参数里没有这个 key，`get(key)` 返回 null，`.toString()` 直接抛 NullPointerException。比如条件写 `args.file_path == "x"` 但当前工具是 Bash（没有 file_path 参数），就会 NPE。**这是真实隐患**，正确写法应该是 `Object v = ctx.toolArgs().get(key); yield v == null ? "" : v.toString();`。

3. **正则语法错误**：`=~ /[/`（未闭合方括号）会让 `Pattern.compile` 抛 `PatternSyntaxException`。

**关键问题：一个 Hook 的条件求值抛异常，会不会导致工具全部无法执行？**

绝对不能。条件求值必须包在 try-catch 里，异常时**降级为"不触发这个 Hook"**（返回 false），而不是让异常冒泡到工具执行主流程。否则一个用户写错的 Hook 条件会让整个 Agent 瘫痪——所有工具调用都在条件求值这一步崩掉。这是"用户配置不可信"原则：框架消费用户输入时，任何解析都要容错。

---

## 12.6 三个特殊标志：once、async、onError

**`once: true`**：

```java
if (h.once()) {
    if (fired.contains(h.id())) return false;  // 已触发过，跳过
    fired.add(h.id());
}
```

一次会话里只触发一次。用于"会话开始时发一条欢迎消息"这类场景——不希望每次都重复触发。

**`async: true`**：

```java
if (h.async()) {
    CompletableFuture.runAsync(() -> {
        HookResult res = executeAction(h, ctx);
        notifications.add(res);  // 把结果放进通知队列，不直接影响主流程
    });
    results.add(new HookResult(h.id(), "(async)", true, false));
    continue;
}
```

异步执行，主 Agent 立刻继续。适合通知类 Hook（Slack、HTTP webhook），不需要等结果。

**`onError: "reject"`**（仅 `pre_tool_use`）：

```java
if (h.reject() || (!result.success() && "reject".equals(h.onError()))) {
    return new PreToolResult(true, result.output());
}
```

两种触发拒绝的方式：
1. `reject: true`：无论动作是否成功，都拒绝工具调用（用于"某些工具在特定条件下绝对禁止"）
2. `onError: reject`：动作执行失败时拒绝（用于"lint 检查失败就不允许提交代码"）

---

## 12.7 模板变量：让动作内容动态化

```java
// HookContext.expand 把 ${var} 替换为实际值
public String expand(String template) {
    String result = template;
    result = result.replace("${event}",     ctx.event().value());
    result = result.replace("${tool}",      ctx.toolName());
    result = result.replace("${file_path}", ctx.filePath());
    // 还支持 ${args.xxx}
    for (var entry : ctx.toolArgs().entrySet()) {
        result = result.replace("${args." + entry.getKey() + "}", entry.getValue().toString());
    }
    return result;
}
```

用于 HTTP body 和命令字符串的动态拼接：

```yaml
action:
  type: http
  body: '{"file": "${args.file_path}", "tool": "${tool}"}'

action:
  type: command
  command: "git add ${args.file_path} && git commit -m 'Auto-committed by CodePal'"
```

---

## 12.8 Hook 和权限系统的协作顺序

在 `StreamingExecutor.executeSingle` 里，权限检查在 Hook 之前：

```
[1] 权限检查（PermissionChecker）
    ↓ DENY → 直接拒绝，不触发 Hook
    ↓ ASK → 弹窗等用户确认
    ↓ ALLOW → 继续

[2] Pre-tool Hook（HookEngine.runPreToolHooks）
    ↓ 通过 → 继续执行工具
    ↓ 拒绝 → 返回错误

[3] 执行工具

[4] Post-tool Hook（HookEngine.runHooks POST_TOOL_USE）
```

**为什么权限在 Hook 之前？**

权限检查是"这个操作允不允许发生"的第一道门，属于安全控制。Hook 是"允许发生时要附带做什么"，属于业务逻辑扩展。如果先跑 Hook（比如发了通知），再被权限拒绝，就产生了"通知发出去了但操作没做"的不一致。

---

## 12.9 面试官可能问的问题

**Q1：你们的 Hook 系统是怎么设计的？**

> Hook 系统监听 Agent 生命周期的 9 种事件（会话开始/结束、每轮开始/结束、发送前/接收后、工具调用前/后、关闭），在事件发生时触发用户配置的动作。4 种动作类型：COMMAND（执行 bash 命令）、PROMPT（注入 LLM 上下文）、HTTP（Webhook 通知）、AGENT（启动子 Agent）。条件表达式支持 `==`/`!=`/`=~`/`=*` 四种操作符和 `&&`/`||`/`!` 布尔组合。`pre_tool_use` 事件支持 `reject: true` 或 `onError: reject` 拦截工具调用。`async: true` 让通知类 Hook 不阻塞主流程。

**Q2：Hook 和权限系统有什么区别？**

> 权限系统是安全控制：这个操作允不允许做。Hook 是业务扩展：做之前/之后要附带做什么。执行顺序是权限在前、Hook 在后——权限通过了才触发 Hook，保证"通知发出去"和"操作真正执行"是一致的。

**Q3：Hook 的 COMMAND 动作会不会成为绕过权限系统的安全漏洞？**

> 这是个很尖锐的问题。Hook 的 command 是**用户自己在 config.yaml 里配置的**，理论上用户能配任意命令，看起来像绕过了权限系统。但要分清信任边界：权限系统防的是"**LLM 被诱导执行危险操作**"（不可信的模型行为），而 Hook 配置是**用户主动写的**（可信的用户意图）——用户配 `rm -rf` 那是用户自己的选择，跟他直接在终端敲没区别，不属于权限系统要防的攻击面。
>
> 但有个真实风险：如果 config.yaml 本身来自不可信来源（比如 clone 了别人的仓库，`.codepal/config.yaml` 里藏了恶意 Hook），那用户一启动 Agent 就中招了。所以更严谨的设计是：**项目级 Hook 配置在首次加载时要用户确认**（"这个项目定义了 3 个 Hook，其中 1 个会执行命令 xxx，是否信任？"），这跟 VS Code 打开不信任工作区要确认是同一个道理。

**Q4：async Hook 的结果放进 notifications 队列，什么时候被消费？失败了用户知道吗？**

> async Hook 用 `CompletableFuture.runAsync` 跑，结果塞进 `notifications` 队列，主 Agent 不等它。这个队列在**下一轮 Agent Loop 开始时被 drain**（和第15章 Teams 的队友消息注入是同一个机制），把结果作为 system reminder 注入上下文，或在 TUI 里显示。问题在于：如果 async Hook **静默失败**（比如 webhook 的 URL 挂了），而没人去读 notifications，用户就完全无感知——以为通知发出去了，其实没有。这是 async 的固有代价：**换来了不阻塞，牺牲了即时的失败反馈**。缓解办法是失败结果也进队列并在 TUI 显式提示（"Hook db-alert 执行失败"），而不是吞掉。

**Q5：条件表达式为什么自己写递归下降解析器，而不用现成的表达式引擎（如 SpEL、Aviator）？**

> 权衡。引入表达式引擎能支持更复杂的语法，但代价是：多一个重依赖、有学习成本、且表达式引擎功能太强反而是安全隐患（SpEL 注入是有名的漏洞，能执行任意代码）。Hook 条件的需求很窄——就是"变量 操作符 值"加上 `&&`/`||`，几十行递归下降就能覆盖，可控、无依赖、无注入风险。这又是"需求匹配方案"：需求简单时，自己写个小解析器比引入重型引擎更合适。

---

## 12.10 生产中可能遇到的问题

**问题1：Hook 命令执行很慢，阻塞了 Agent**

根本原因：command 类型 Hook 默认超时 10 分钟，如果命令卡住（如等待网络），会阻塞整个工具执行。

解法：
1. 对于不需要结果的通知类 Hook，加 `async: true`
2. 为耗时命令设置合理的 `timeout`（比如 `30s`）

**问题2：`=~` 正则写错，条件永远不匹配**

常见错误：忘记转义 `.`，`\.sql` 写成了 `.sql`，导致 `.php` 也匹配到了。

调试方法：在 TUI 里手动触发一次相关工具调用，看 Hook 是否被触发。也可以先用简单的 `tool == "EditFile"` 验证 Hook 基本工作，再逐步加上文件路径过滤条件。

**问题3：`once: true` 的 Hook 在重启后又触发了**

`fired` 集合是内存里的 `HashSet`，进程重启后清空。如果需要真正的"整个项目只触发一次"，需要把触发记录写到文件（比如 `.codepal/hooks-fired.json`），或者在 Hook 的命令里自己检查标志文件是否存在。

**问题4（用户视角）：用户配的 Hook 拖慢每次工具调用，却不知道是 Hook 导致的**

同步 Hook（没配 `async`）会在每次匹配的工具调用前后执行。如果用户配了个"每次 EditFile 前跑全量 lint"的 Hook，会发现 Agent 变得很慢，但不会立刻联想到是自己配的 Hook——只觉得"框架卡"。

解法：Hook 执行耗时应计入可观测性（第8章的 Metrics），`/metrics` 里能看到"Hook java-lint 平均耗时 12s、触发 40 次"，让用户能定位到慢的根源。可观测性不只是给框架开发者用的，也是给用户排查自己配置问题用的。

**问题5（用户视角）：Hook 静默失败，用户毫无感知**

async Hook 失败无反馈（Q4 已述）；即使是同步 Hook，如果 `on_error` 没配 `reject`，失败了也只是记一条结果、不影响流程，用户看不到。用户以为"改了 .sql 会自动发 Slack 通知"，结果 webhook 早就失效了，一直没通知，用户却以为在正常工作。

解法：Hook 执行失败应该有一个**默认可见**的提示（哪怕只是 TUI 角落一行红字），而不是默认静默。让用户能及时发现"我的自动化坏了"。

**问题6（用户视角）：`&&` / `||` 混合优先级，用户理解错**

`a == "x" && b == "y" || c == "z"` ——用户以为是 `a && (b || c)`，实际按 `splitComposite` 的实现可能是从左到右无优先级的 `(a && b) || c`。布尔优先级是新手极易踩的坑。

解法：一是文档明确说明求值顺序（当前实现是**从左到右线性求值，`&&` 和 `||` 同级**，不遵循数学上 `&&` 优先于 `||`）；二是建议用户避免混用，复杂条件拆成多个 Hook 更清晰。这是"实现简单"带来的语义代价，必须在文档里讲清楚，否则用户配出来的条件行为和预期不符还找不到原因。

---

*下一章：SubAgent — 子 Agent 的设计与实现*
