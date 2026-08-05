# 第4章：让 Agent 自己干活

> 目标：理解 Agent Loop 的完整实现，掌握每一行代码背后的设计原因，能在面试中完整描述 Agent 的运行机制。
> 关键文件：`agent/Agent.java`、`agent/AgentEvent.java`、`conversation/ConversationManager.java`

---

## 4.1 前三章的汇合点

学到这里，你已经知道了：
- **第1章**：Agent 是什么，ReAct 循环的概念
- **第2章**：LLM 如何通信，流式调用怎么工作
- **第3章**：工具是什么，并发分批怎么执行

**第4章**是这三者的汇合点：`Agent.java` 把 LlmClient、ToolRegistry、ConversationManager 串联起来，形成一个完整的自动运行循环。

先看整体结构，再逐段深入。

---

## 4.2 Agent 的字段：它需要哪些依赖

```java
public class Agent {

    // 核心依赖（构造器注入）
    private final LlmClient client;          // 和 LLM 通信
    private final ToolRegistry registry;     // 工具注册表
    private final String protocol;           // "anthropic" 或 "openai"
    private final int contextWindow;         // 上下文窗口大小（token 数）
    private final int maxOutput;             // 最大输出 token 数

    // 可选组件（setter 注入）
    private PermissionChecker checker;       // 权限检查器
    private HookEngine hookEngine;           // 生命周期 Hook
    private int maxIterations;               // 最大轮次限制
    private String workDir;                  // 工作目录
    private String sessionId;               // 会话 ID（用于持久化）

    // 状态对象
    private final CompactTrackingState compactTracking = new ...;  // 压缩断路器
    private final RecoveryState recoveryState = new RecoveryState(); // 压缩恢复快照
    private ContentReplacementState replacementState = new ...;     // 工具结果落盘状态

    // 非阻塞记忆召回
    private CompletableFuture<String> memoryRecallFuture;
}
```

**构造器注入 vs Setter 注入的选择：**

`client`、`registry`、`protocol` 是核心依赖，没有它们 Agent 无法工作，所以放构造器强制提供。`checker`、`hookEngine` 是可选的（测试场景或简单场景可以不用），用 setter 注入，不传就跳过。这是依赖注入的最佳实践。

---

## 4.3 启动：Virtual Thread + 双队列

```java
// 外部调用入口
public BlockingQueue<AgentEvent> run(ConversationManager conv) {
    var queue = new LinkedBlockingQueue<AgentEvent>(64);
    run(conv, queue);
    return queue;
}

// 允许外部传入 queue（TUI 提前创建，立即开始轮询）
public void run(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
    Thread.startVirtualThread(() -> {
        try {
            agentLoop(conv, queue);
        } catch (Exception e) {
            putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
        }
    });
}
```

两个 `run` 方法的区别：第一个自己创建 queue 再返回，调用方拿到 queue 后开始轮询。第二个让调用方传进来 queue，这样 TUI 可以先创建好 queue，在 Agent 还没启动时就开始等待，不会错过最早的事件。

**为什么用 Virtual Thread？**

Agent 大部分时间在等 LLM 返回（IO 密集型）。Virtual Thread 遇到阻塞 IO 会自动"挂起"，让出 CPU 给其他任务，不占用 OS 线程资源。多个 Agent 并发跑时（多 Agent 协作场景）优势明显。

---

## 4.4 agentLoop：主循环全解

主循环是一个无限 `for` 循环，每次迭代是一轮"思考 + 行动"。下面逐段拆解每一步在做什么，以及为什么这样设计。

### 第1步：注入长期记忆（只在第一轮）

```java
conv.injectLongTermMemory(instructions, memoryContent);
```

在循环开始前（不是在循环内），把两类内容插到对话历史最前面：
- `instructions`：CLAUDE.md / `.codepal/CLAUDE.md` 里的项目指令
- `memoryContent`：用户的跨会话记忆

实现在 `ConversationManager` 里：

```java
public void injectLongTermMemory(String instructions, String memories) {
    if (ltmInjected) return;  // 防止重复注入
    // 包在 <system-reminder> 标签里，作为第一条 user 消息插入
    String wrapped = "<system-reminder>\n" + instructions + "\n" + memories + "\n</system-reminder>";
    history.add(0, new Message("user", wrapped));
    ltmInjected = true;
}
```

注意 `ltmInjected` 标记：如果压缩后重新注入（`conv.resetLtmInjected()`），需要再调一次，但不能重复注入。

---

### 第2步：安全门 — 迭代次数和中断检查

```java
for (int iteration = 1; ; iteration++) {

    // 超过最大轮次限制
    if (maxIterations > 0 && iteration > maxIterations) {
        putSafe(queue, new AgentEvent.ErrorEvent("Agent reached maximum iterations (%d)".formatted(maxIterations)));
        break;
    }

    // 外部中断（Ctrl+C、线程被 interrupt）
    if (Thread.currentThread().isInterrupted()) break;
```

为什么需要最大轮次限制？防止 Agent 进入死循环（模型一直认为任务没完成，不断调用工具，永远不会停）。生产环境里设置一个合理的上限（比如 100 轮）是必要的保护。

---

### 第3步：注入后台通知

```java
if (notificationFn != null) {
    for (String note : notificationFn.get()) {
        conv.addSystemReminder(note);
    }
}
```

多 Agent 协作场景下（第15章），后台的子 Agent 完成任务后会发通知。这里在每轮开始时检查通知队列，把通知作为 `<system-reminder>` 注入对话历史，让 LLM 知道后台有新进展。

---

### 第4步：计算本轮工具列表

```java
var iterToolSchemas = registry.getAllSchemas(protocol);
if (toolNameFilter != null) {
    iterToolSchemas = iterToolSchemas.stream()
            .filter(schema -> toolNameFilter.test(schema.get("name").toString()))
            .toList();
}
```

每轮重新计算工具列表，而不是在 Agent 初始化时算一次。原因：Skill 系统（第11章）可以动态过滤工具，让某个子任务只能使用特定工具子集，限制范围。

---

### 第5步：MCP 延迟工具提示

```java
var deferredNames = registry.getDeferredToolNames();
if (!deferredNames.isEmpty()) {
    // 告诉 LLM：这些工具存在，但 schema 没加载，需要先调 ToolSearch 才能用
    conv.addSystemReminder("The following deferred tools are available via ToolSearch: ...");
}
```

MCP 工具太多时（第7章），不把所有工具的 schema 都发给 LLM（太占 token），而是只告诉 LLM"这些工具存在"，LLM 需要时调 `ToolSearch` 按需加载具体 schema。这是 MCP 延迟加载机制的触发点。

---

### 第6步：两层上下文压缩

```java
// Layer 1：工具结果大的直接落盘
List<ContentReplacementRecord> newRecords = ToolResultBudget.apply(conv, sessionDir, replacementState);

// Layer 2：token 接近上限时 LLM 生成摘要
String compactMsg = ContextCompactor.manage(
        conv, client, contextWindow, maxOutput, workDir, sessionId,
        compactTracking, recoveryState, iterToolSchemas, conv.getMessages());

if (compactMsg != null && !compactMsg.isEmpty()) {
    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));  // 通知 TUI 显示"正在压缩"
}

// 压缩后需要重新注入长期记忆（历史被替换了）
if (conv.size() < sizeBefore) {
    conv.clearUsageAnchor();
    conv.resetLtmInjected();
    conv.injectLongTermMemory(instructions, memoryContent);
}
```

注意压缩后的三步重置：
1. `clearUsageAnchor()`：压缩前的 token 锚点已失效，清掉重新建立
2. `resetLtmInjected()`：历史被替换了，长期记忆也被删掉了，需要重新注入
3. `injectLongTermMemory()`：立刻重新注入，保证下一次 LLM 调用还能看到项目指令

---

### 第7步：调用 LLM，消费流式事件（内层循环）

```java
var streamQueue = client.stream(conv, tools);

var text = new StringBuilder();
var thinkingBlocks = new ArrayList<ThinkingBlock>();
var toolCalls = new ArrayList<ToolCallInfo>();
String stopReason = "end_turn";
int turnInput = 0, turnOutput = 0;

while (true) {
    StreamEvent event = streamQueue.poll(30, TimeUnit.SECONDS);

    if (event == null) {
        putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
        return;
    }

    switch (event) {
        case StreamEvent.TextDelta td -> {
            text.append(td.text());
            putSafe(queue, new AgentEvent.StreamText(td.text()));  // 实时推给 TUI
        }
        case StreamEvent.ThinkingComplete tc ->
            thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
        case StreamEvent.ToolCallComplete tcc ->
            toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
        case StreamEvent.StreamEnd se -> {
            stopReason = se.stopReason();
            turnInput = se.inputTokens();
            turnOutput = se.outputTokens();
        }
        case StreamEvent.Error err -> { streamError = true; }
    }

    if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
}
```

**内层循环的职责**：把 LLM 推过来的原始 `StreamEvent` 转化成两类东西：
1. 推给 TUI 显示的 `AgentEvent`（实时文字）
2. 收集起来供后续处理的数据（toolCalls、stopReason、token 用量）

**30 秒超时**：`streamQueue.poll(30, TimeUnit.SECONDS)`，如果 30 秒没有新事件，认为 stream 断掉了，发错误事件。这是网络问题的兜底。

**Java 21 sealed interface + pattern matching switch**：
```java
switch (event) {
    case StreamEvent.TextDelta td -> { ... }
    case StreamEvent.ToolCallComplete tcc -> { ... }
    ...
}
```
`StreamEvent` 是 sealed interface，编译器能检查所有子类型是否都被处理，不会漏掉。Java 21 的模式匹配 switch 比传统 `instanceof` 检查更简洁、类型安全。

---

### 第8步：错误恢复

```java
if (streamError) {
    // 上下文超长 → 强制压缩后重试
    if (lastErr.contains("context") || lastErr.contains("too long")) {
        if (contextRetries < 3) {
            contextRetries++;
            ContextCompactor.forceCompact(...);
            continue;  // 重试当前迭代
        }
    }
    // rate limit → 等 5 秒重试
    if (lastErr.toLowerCase().contains("rate limit")) {
        Thread.sleep(5000);
        continue;
    }
    break;  // 其他错误：退出循环
}
```

**三种处理策略对应三种错误类型：**

| 错误类型 | 是否可恢复 | 处理方式 |
|---------|-----------|---------|
| context too long | 是（压缩后可继续） | 强制压缩 + `continue` |
| rate limit | 是（等一等就好） | `Thread.sleep(5000)` + `continue` |
| 其他（网络、认证等） | 否 | `break`，退出循环 |

`contextRetries < 3`：最多重试 3 次，防止压缩后还是超长（极端情况）。

---

### 第9步：更新 token 统计和 Metrics

```java
totalInput += turnInput;
totalOutput += turnOutput;
putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));
MetricsCollector.getInstance().recordTokens(turnInput, turnOutput);
MetricsCollector.getInstance().recordTurnComplete();
```

两个目的：
1. `UsageEvent` 推给 TUI，实时显示当前会话消耗的 token 数（用户能看到费用）
2. `MetricsCollector` 埋点，供可观测性系统分析（第8章会讲）

---

### 第10步：max_tokens 处理

```java
if ("max_tokens".equals(stopReason)) {
    if (!maxTokensEscalated) {
        // 第一次：把 maxTokens 上限从默认值提升到 64000
        maxTokensEscalated = true;
        client.setMaxOutputTokens(MAX_TOKENS_CEILING);  // 64000
        conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped...");
        continue;  // 重试
    } else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {  // MAX = 3
        // 第 2-3 次：提示"把剩余工作拆细"
        outputRecoveries++;
        conv.addUserMessage("Output token limit hit. Break remaining work into smaller pieces.");
        continue;
    }
    // 第 4 次：放弃，当作正常完成
}
```

LLM 输出被截断（`stop_reason = "max_tokens"`）时的三级应对：

**为什么先升级 maxTokens 再说？** 有些任务（比如生成一大段代码）确实需要更多输出空间，第一次遇到截断时先给它更多空间，不要轻易放弃。

**为什么第 2-3 次要让它"拆细"？** 如果提升了上限还是不够，说明任务本身太大，需要分拆成更小的步骤。

---

### 第11步：保存 assistant 消息，更新锚点

```java
// 把这轮的 assistant 消息保存进历史
var toolUseBlocks = toolCalls.stream()
        .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
        .toList();
conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);

// 用真实 token 数更新估算锚点（第8章压缩用）
if (turnInput > 0 || turnOutput > 0) {
    conv.recordUsageAnchor(turnInput, turnOutput, turnCacheRead, turnCacheCreation);
}
```

**为什么要更新锚点？** 上下文压缩的触发判断依赖 token 估算。用真实 API 返回的 token 数做锚点，比纯字符估算准确很多，尤其是 Prompt Cache 命中时（实际消耗比估算小很多）。

---

### 第12步：判断退出 OR 执行工具

```java
// 没有工具调用 → 任务完成，退出循环
if (toolCalls.isEmpty()) {
    if (fileHistory != null) fileHistory.makeSnapshot(conv.size(), summary);
    putSafe(queue, new AgentEvent.LoopComplete(iteration));
    break;
}

// 有工具调用 → 执行工具
var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState);
var results = executor.executeAll(callInfos);

// 工具结果加入对话历史（tool_use/tool_result 配对）
var resultBlocks = results.stream()
        .map(r -> new ToolResultBlock(r.toolId(), r.output(), r.isError()))
        .toList();
conv.addToolResultsMessage(resultBlocks);
```

这里是整个循环的核心分叉点：
- `toolCalls.isEmpty()` → `stop_reason` 是 `end_turn`，模型认为任务完成，发 `LoopComplete` 事件，break 退出
- 否则 → 执行工具，结果写进历史，进入下一轮

**tool_use/tool_result 配对**：
```
历史里的结构：
  [assistant 消息] 包含 ToolUseBlock(id="toolu_001", name="ReadFile", args={...})
  [user 消息]     包含 ToolResultBlock(toolUseId="toolu_001", content="文件内容...")
```

Anthropic API 要求这两条必须成对出现，且 `toolUseId` 对应。`conv.addToolResultsMessage(resultBlocks)` 把所有工具结果放进同一条 user 消息，保证一一对应。

---

### 第13步：非阻塞记忆召回

```java
// 工具执行完后检查 prefetch 是否就绪
if (memoryRecallFuture != null && !memoryRecallConsumed) {
    if (memoryRecallFuture.isDone()) {
        String recall = memoryRecallFuture.getNow("");
        if (recall != null && !recall.isEmpty()) {
            conv.addSystemReminder(recall);
        }
        memoryRecallConsumed = true;
    }
}
```

记忆系统的非阻塞召回设计：在 Agent 发起 LLM 请求的同时，后台异步查询长期记忆（基于当前对话内容做语义搜索）。等第一轮工具执行完后（通常有几秒），记忆召回也结束了，这时候注入到对话历史里。

这样"记忆召回"和"第一轮 LLM 调用 + 工具执行"是并行的，不额外增加延迟。

---

### 第14步：Plan Mode 检测

```java
boolean exitPlanCalled = toolCalls.stream()
        .anyMatch(tc -> "ExitPlanMode".equals(tc.toolName));
if (exitPlanCalled) {
    putSafe(queue, new AgentEvent.TurnComplete(iteration));
    putSafe(queue, new AgentEvent.LoopComplete(iteration));
    break;
}
```

Plan Mode 是一个特殊模式：Agent 只能读，不能写，专心规划方案。当模型调用 `ExitPlanMode` 工具（说明规划完成），立刻退出循环，不管后面还有没有工具调用。

---

## 4.5 对话历史的完整结构

一次完整的多轮任务之后，`ConversationManager` 里的历史长这样：

```
[0]  user:      <system-reminder>（长期记忆 + 项目指令）
[1]  user:      用户的问题："帮我修复 Agent.java 里的 bug"
[2]  assistant: 文字("我来看一下") + ToolUseBlock(ReadFile, Agent.java)
[3]  user:      ToolResultBlock(ReadFile 结果："文件内容...")
[4]  assistant: 文字("找到了，是第 50 行") + ToolUseBlock(EditFile, Agent.java)
[5]  user:      ToolResultBlock(EditFile 结果："已修改，+1 addition -1 removal")
[6]  assistant: 文字("修复完成！")  ← toolCalls 为空，循环在这里结束
```

**为什么 role 是 user/assistant 交替的？**

Anthropic API 的硬性要求：消息必须严格 user/assistant 交替。工具结果（step 3, 5）虽然是系统执行的，但在 API 层面表示为 user 消息（因为是"人/系统"的反馈）。

---

## 4.6 AgentEvent：事件驱动架构

```java
public sealed interface AgentEvent {
    record StreamText(String text) implements AgentEvent {}          // 实时文字
    record ThinkingText(String text) implements AgentEvent {}        // 思考过程
    record ToolUseEvent(String toolId, String toolName, ...) implements AgentEvent {}   // 工具被调用
    record ToolResultEvent(String toolId, String toolName, String output, ...) implements AgentEvent {} // 工具结果
    record TurnComplete(int turn) implements AgentEvent {}           // 一轮完成
    record LoopComplete(int totalTurns) implements AgentEvent {}     // 整个任务完成
    record UsageEvent(int inputTokens, int outputTokens) implements AgentEvent {}       // token 用量
    record ErrorEvent(String message) implements AgentEvent {}       // 出错了
    record CompactEvent(String message) implements AgentEvent {}     // 正在压缩上下文
    record RetryEvent(String reason, long waitMs) implements AgentEvent {}  // 正在重试
    record PermissionRequestEvent(..., CompletableFuture<PermissionResponse> future) implements AgentEvent {} // 需要权限确认
}
```

这个事件系统让 Agent 的内部状态变化对外完全透明。TUI 消费这些事件来驱动 UI 更新：

| 事件 | TUI 的响应 |
|-----|-----------|
| `StreamText` | 实时追加文字到聊天区域 |
| `ToolUseEvent` | 显示"正在调用 ReadFile..." |
| `ToolResultEvent` | 折叠显示工具结果（可展开） |
| `PermissionRequestEvent` | 弹出权限确认对话框 |
| `CompactEvent` | 显示"正在压缩上下文..." |
| `LoopComplete` | 显示 token 用量，保存会话 |

**`PermissionRequestEvent` 的异步等待设计：**

```java
record PermissionRequestEvent(
    String toolName,
    String description,
    CompletableFuture<PermissionResponse> future  // ← 关键
) implements AgentEvent {}
```

Agent 把 future 作为事件的一部分传给 TUI，TUI 显示弹窗后，用户点击时调用 `future.complete(response)`，Agent 线程的 `future.get()` 解除阻塞继续执行。

两个线程没有直接引用，只通过 `CompletableFuture` 通信。这是跨线程异步通信的经典模式。

---

## 4.7 finally 块：保证 LoopComplete 一定被发出

```java
try {
    for (int iteration = 1; ; iteration++) {
        // ... 主循环
    }
} finally {
    if (!loopCompleted) {
        putSafe(queue, new AgentEvent.LoopComplete(0));
    }
}
```

无论循环是正常结束、遇到异常、还是被中断，`finally` 块都能保证 `LoopComplete` 事件一定被发出。

为什么重要？TUI 等待 `LoopComplete` 来做收尾工作（保存会话、重置 UI 状态）。如果循环因为异常崩掉，没有 `LoopComplete`，TUI 就会永远等待，界面卡死。`finally` 块是这个保证的关键。

---

## 4.8 完整流程图

```
agentLoop 开始
    │
    ├─ 注入长期记忆（仅一次）
    │
    └─ for (iteration = 1; ; iteration++)
         │
         ├─ 安全检查（maxIterations、中断）
         ├─ 注入后台通知
         ├─ 计算工具列表（含过滤）
         ├─ MCP 延迟工具提示
         ├─ Plan Mode 提示（如果在 Plan Mode）
         │
         ├─ [Layer 1 压缩] 工具结果落盘
         ├─ [Layer 2 压缩] token 接近上限时 LLM 摘要
         │    ├─ 压缩发生 → 重置锚点、重新注入记忆
         │
         ├─ client.stream(conv, tools)  ← 调用 LLM
         │    └─ while(true) 消费 StreamEvent
         │         ├─ TextDelta → 推给 TUI
         │         ├─ ToolCallComplete → 加入 toolCalls 列表
         │         └─ StreamEnd / Error → 退出内层循环
         │
         ├─ [错误处理]
         │    ├─ context too long → 强制压缩，continue
         │    ├─ rate limit → sleep 5s，continue
         │    └─ 其他 → break
         │
         ├─ 更新 token 统计 + Metrics
         ├─ 处理 max_tokens（升级 / 续写 / 放弃）
         ├─ 保存 assistant 消息进历史
         ├─ 更新 token 锚点
         │
         ├─ toolCalls 为空？
         │    ├─ 是 → putSafe(LoopComplete)，break ← 任务完成
         │    └─ 否 →
         │         ├─ StreamingExecutor.executeAll(toolCalls)  ← 执行工具（含并发分批）
         │         ├─ conv.addToolResultsMessage(results)  ← 工具结果写入历史
         │         ├─ 检查记忆召回 Future
         │         ├─ ExitPlanMode 检查
         │         └─ putSafe(TurnComplete)，进入下一轮
         │
finally: 确保 LoopComplete 一定被发出
```

---

## 4.9 面试官可能问的问题

**Q：完整描述一下你们 Agent Loop 的流程。**

> 每轮开始先检查上下文 token，超阈值触发两层压缩（大工具结果落盘 + LLM 生成摘要）。然后把对话历史、系统指令、工具列表发给 LLM，流式接收响应，边收边推给 TUI 显示。响应结束后判断 stop_reason：如果是 end_turn 且没有工具调用，发 LoopComplete 退出；如果有工具调用，按并发安全性分批执行，结果写入历史，进入下一轮。整个过程穿插着三种错误自愈：context 超长强制压缩重试，rate limit 等 5 秒重试，max_tokens 分三级处理。finally 块保证无论什么原因退出，LoopComplete 一定被发出。

**Q：你们是怎么处理 Agent 跑偏或死循环的？**

> 三个层次的防护：
> 1. `maxIterations` 设置最大轮次上限，超过直接报错退出，防止无限循环
> 2. 权限系统（第6章）对每次工具调用做检查，超出许可范围的操作被拦截，拦截本身作为错误结果回传给 LLM，让它知道"这条路走不通"
> 3. 工具执行有超时保护（BashTool 最长 600 秒），不会因为一个命令卡住整个 Agent

**Q：为什么对话历史里工具结果要放在 user 消息里？**

> 这是 Anthropic API 的协议要求：消息角色必须严格 user/assistant 交替，且 tool_use 和 tool_result 必须成对出现。工具结果表示"来自系统/环境的反馈"，在 API 层面映射为 user 角色。这个约束在上下文压缩时要特别注意：压缩的切断点不能把一对 tool_use/tool_result 拆开，否则 API 报 400 错误。

**Q：CompletableFuture 在权限确认里怎么用的？**

> Agent 线程执行到权限 ASK 时，创建一个 `CompletableFuture<PermissionResponse>`，连同工具名和描述一起打包成 `PermissionRequestEvent` 推进 BlockingQueue，然后调 `future.get(5, TimeUnit.MINUTES)` 阻塞等待。TUI 线程消费这个事件，显示权限确认弹窗，用户点击后调 `future.complete(response)`，Agent 线程解除阻塞，根据 response 决定是否继续执行。两个线程通过 future 通信，没有直接引用，完全解耦。

**Q（追问）：14 步太多了，一个最小的 Agent Loop 到底需要哪几步？**

> 剥掉所有生产加固，最小 Loop 只有 4 步：①把对话历史 + 工具列表发给 LLM；②看 LLM 返回里有没有 tool_use；③有就执行工具、把结果写回历史；④没有就结束。就这 4 步，循环起来就是一个能跑的 Agent。**其余 10 步全是 Harness 层的生产加固**——压缩（防超限）、错误自愈（防崩溃）、权限（防越权）、记忆召回（增强上下文）、Metrics（可观测）、finally 兜底（防卡死）。这个"4 步主干 + 10 步加固"的划分很重要：面试时先讲清 4 步主干证明你懂本质，再讲 10 步加固证明你懂生产——比一上来堆 14 步让人抓不住重点强得多。

**Q（追问）：stop_reason 有哪几种？分别怎么处理？**

> Anthropic 的 stop_reason 主要有四种，每种处理不同：
> - **`end_turn`**：模型自然说完了。这时若没有 tool_use → 任务完成，发 LoopComplete 退出；若有 tool_use → 执行工具进下一轮。
> - **`tool_use`**：模型明确要调工具而停下。执行工具、回填结果、继续循环。
> - **`max_tokens`**：输出被 token 上限截断。分三级处理——先升级输出上限重试，再让它续写，最后放弃（4.4 第10步）。
> - **`stop_sequence`**：命中了预设的停止序列（我们一般不用，用到时按 end_turn 类似处理）。
>
> 关键是**别把所有停止都当成"完成"**——只有 `end_turn` 且无 tool_use 才是真完成，`max_tokens` 是"话没说完被打断"，处理不当会丢失内容。

---

## 4.10 生产中可能遇到的问题

**问题1：Agent 完成后 TUI 界面没有响应（卡死）**

原因：`LoopComplete` 事件没有被发出，TUI 在无限等待。

常见触发点：agentLoop 抛出了未被 catch 的异常，绕过了 `putSafe(LoopComplete)` 这行。

CodePal 的解法：`finally` 块兜底，无论什么原因退出都发 `LoopComplete`：
```java
} finally {
    if (!loopCompleted) {
        putSafe(queue, new AgentEvent.LoopComplete(0));
    }
}
```
自己写框架时一定要加这个保护。

**问题2：多次注入长期记忆，对话历史里出现重复指令**

原因：压缩后调了 `conv.resetLtmInjected()` 重置标记，但没有及时调 `injectLongTermMemory()`，下一轮又重置了一次，就注入了两遍。

解法：每次 `resetLtmInjected()` 之后必须立即 `injectLongTermMemory()`，形成"重置 + 立即注入"的原子操作。

**问题3：`putSafe` 被中断后后续事件都丢失**

```java
private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
    try {
        queue.put(event);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();  // 恢复中断标记
    }
}
```

如果不调 `Thread.currentThread().interrupt()` 恢复中断标记，后续代码的 `isInterrupted()` 检查就失效了，中断信号被吞掉，Agent 无法正常退出。这是 Java 并发编程里的经典坑。

**问题4：rate limit 只等 5 秒，高负载时仍然持续报错**

5 秒是一个经验值，对低负载场景够用，但在高并发或高频调用场景可能不够。

优化方向：指数退避（Exponential Backoff）+ Jitter。第一次等 5 秒，第二次等 10 秒，第三次等 20 秒，加上随机抖动避免"惊群效应"（多个 Agent 同时重试，同时再次触发 rate limit）。

**问题5（用户视角）：任务跑了很多轮，用户等得焦虑，不知道还要多久**

Agent 一轮轮跑，用户盯着屏幕，不知道进度、不知道还剩多少、不知道是不是卡了。长任务的"等待焦虑"是自主 Agent 的普遍体验问题。

解法：用 AgentEvent（4.6）把每一步都实时暴露给用户——`ToolUseEvent` 显示"正在读取 X 文件"、`TurnComplete` 显示"第 5 轮"、`CompactEvent` 显示"正在压缩上下文"。让用户始终看到 Agent 在动、在做什么，比进度条更重要的是"可见的活动感"。沉默是焦虑的来源。

**问题6（用户视角）：maxIterations 到了任务没做完，用户看到的是一个突兀的报错**

Agent 跑到轮次上限被强制退出，用户看到"Max iterations reached"就懵了——任务到哪一步了？做了多少？要不要接着跑？

解法：达到 maxIterations 退出时，不应只报错，而应给一个**状态交接**——"已达到最大轮次（30），任务可能未完成。已完成：修改了 3 个文件；未完成：测试尚未通过。是否继续？"让用户能决策（继续/放弃/调整），而不是面对一个死胡同。这和第15章 Teammate 的"空闲通知"是一个思路：中断时留下可续接的状态。

---

*下一章：System Prompt 设计 — 如何让 Agent 的基础行为符合预期*
