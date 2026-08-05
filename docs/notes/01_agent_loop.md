# 模块一：Agent Loop 主循环

> 对应简历亮点：所有 8 条亮点的底层基础
> 关键文件：`src/main/java/com/codepal/agent/Agent.java`

---

## 1. 先建立直觉：Agent 是什么

在回答面试题之前，先建立一个直觉模型。

你可以把 Agent 理解成一个**不停转动的车轮**：

```
用户输入
  ↓
[调用 LLM] ← 把对话历史 + 工具列表发过去
  ↓
LLM 返回（文字 or 工具调用）
  ↓
如果是文字 → 输出给用户，结束
如果是工具调用 → 执行工具，把结果塞回历史 → 下一圈
```

这个"不停转"就是 **Agent Loop**，也叫 **ReAct 循环**（Reasoning + Acting）。

**与普通 ChatGPT 的本质区别**：普通 Chat 一问一答，一轮就结束。Agent Loop 里，模型可以连续调用几十个工具，每次工具结果都成为下一轮的输入，直到模型认为任务完成为止。

---

## 2. 代码整体结构：三层嵌套

```java
public class Agent {
    // 字段：依赖的各种组件
    
    public void run(ConversationManager conv) { ... }       // 入口（启动虚拟线程）
    
    private void agentLoop(...) {                           // 真正的主循环
        for (int iteration = 1; ; iteration++) {            // 无限循环
            // 第一步：上下文预处理（压缩等）
            // 第二步：调用 LLM，收流式事件
            // 第三步：判断是否有工具调用
            // 第四步：执行工具，结果塞回历史
            // 第五步：检查是否退出
        }
    }
}
```

三层：外层是 `run()` 启动线程，中层是 `agentLoop()` 的 `for` 无限循环，内层是每轮里对 LLM 流式事件的处理 `while(true)`。

---

## 3. 入口：Virtual Thread

```java
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

### 为什么用 Virtual Thread？

这是 Java 21 的新特性。

**传统线程**（Platform Thread）：每个线程对应操作系统的一个真实线程，创建成本高（~1MB 栈内存），最多几千个。

**虚拟线程**（Virtual Thread）：由 JVM 调度，创建成本极低（~几KB），可以开几百万个。当虚拟线程遇到 IO 阻塞（比如等 LLM 返回），JVM 会自动把它"挂起"，让出 CPU 给其他线程，IO 完成后再"唤醒"。

**Agent 场景为什么完美适配**：Agent Loop 的主要时间都在等 LLM 流式返回（IO 密集），用虚拟线程可以让多个 Agent 并发跑而不浪费 CPU。

### BlockingQueue 是什么？

`BlockingQueue<AgentEvent>` 是 Agent 和 UI 之间的"传送带"。

```
Agent 线程                    UI 线程（TUI）
   │                              │
   │  putSafe(queue, event) ──→  │ queue.take()
   │                              │  渲染到终端
```

Agent 把事件（流式文字、工具调用、错误等）放进 queue，UI 从 queue 取出来渲染。两个线程完全解耦，互不干扰。

**为什么是 BlockingQueue（阻塞队列）而不是普通 List？**：如果 UI 消费慢，queue 满了，`put()` 会阻塞 Agent（背压机制），防止内存爆炸。容量是 64：`new LinkedBlockingQueue<AgentEvent>(64)`。

---

## 4. agentLoop 详解：逐段拆解

### 4.1 循环开始前：注入长期记忆

```java
conv.injectLongTermMemory(instructions, memoryContent);
```

在第一轮开始前，把两类内容插到对话历史最前面：
- `instructions`：CLAUDE.md / .codepal/CLAUDE.md 里的项目指令
- `memoryContent`：用户的长期记忆（比如"我喜欢简洁的代码风格"）

`ConversationManager.injectLongTermMemory()` 里可以看到，它把这些内容包在 `<system-reminder>` 标签里，作为一条 user 消息插入。这样模型就知道项目背景了。

**面试切入点**：这就是 System Prompt 的工程实现 —— 不是简单的一个字符串，而是结构化地注入多个来源的指令。

### 4.2 循环内：五件事

```
iteration 1, 2, 3, ...（无限循环）
  ├── 安全门：超过 maxIterations 就报错退出
  ├── 安全门：线程被中断就退出
  ├── [Step A] 注入后台通知（background task 完成的消息）
  ├── [Step B] 处理 MCP 延迟工具（deferred tools）提示
  ├── [Step C] Plan Mode 处理
  ├── [Step D] Layer 1 上下文压缩：工具结果落盘
  ├── [Step E] Layer 2 上下文压缩：LLM 生成摘要
  ├── [Step F] 调用 LLM，收流式事件
  ├── [Step G] 错误恢复（rate limit / context too long）
  ├── [Step H] 工具调用为空 → 输出 LoopComplete，退出
  └── [Step I] 执行工具 → 结果塞回历史 → 下一圈
```

---

### 4.3 Step F：流式调用 LLM

```java
var streamQueue = client.stream(conv, tools);

while (true) {
    StreamEvent event = streamQueue.poll(30, TimeUnit.SECONDS);
    
    switch (event) {
        case StreamEvent.TextDelta td -> { text.append(...); putSafe(queue, new AgentEvent.StreamText(...)); }
        case StreamEvent.ToolCallComplete tcc -> { toolCalls.add(...); }
        case StreamEvent.StreamEnd se -> { stopReason = se.stopReason(); ... }
        case StreamEvent.Error err -> { streamError = true; }
    }
    
    if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
}
```

**流式（Streaming）是什么？**

普通调用：等 LLM 把整段回复生成完再返回，用户盯着空白屏等 10 秒。

流式调用：LLM 每生成一个字就立刻发过来，像打字机一样实时显示。背后是 HTTP SSE（Server-Sent Events）或 WebSocket。

**两个嵌套队列**：

外层的 `queue` 是 Agent → TUI 的传送带。内层的 `streamQueue` 是 LLM HTTP 流 → Agent 的传送带。Agent 把 LLM 推过来的原始事件转化成 `AgentEvent`，推给 TUI。

**Java 21 sealed interface + pattern matching**：

```java
switch (event) {
    case StreamEvent.TextDelta td -> { ... }
    case StreamEvent.ToolCallComplete tcc -> { ... }
    ...
}
```

这用了 Java 21 的 switch pattern matching。`StreamEvent` 是 sealed interface，所有子类型都在里面列举好了，编译器能检查 switch 分支是否穷举，不会漏掉任何事件类型。这是现代 Java 的重要特性，面试可以提。

---

### 4.4 Step H：判断退出

```java
if (toolCalls.isEmpty()) {
    putSafe(queue, new AgentEvent.LoopComplete(iteration));
    break;
}
```

**`stop_reason` 的含义**：

LLM 返回时会带一个 `stop_reason`：
- `"end_turn"`：模型认为任务完成，正常结束。对应 toolCalls 为空，Loop 退出。
- `"tool_use"`：模型要调用工具，继续下一圈。
- `"max_tokens"`：输出 token 到达上限，被截断了（下面有专门处理）。

---

### 4.5 Step I：工具执行

```java
var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState);
var results = executor.executeAll(callInfos);
conv.addToolResultsMessage(resultBlocks);
```

一句话：把模型要调用的工具交给 `StreamingExecutor` 执行，结果作为一条 user 消息塞回对话历史。

这里有两个核心设计——**并发分批**（StreamingExecutor 的核心）和**权限检查**（PermissionChecker），放到后续模块详细讲。

---

### 4.6 错误恢复：两种自愈

**① context too long（上下文超长）**

```java
if (lastErr.contains("context") || lastErr.contains("too long")) {
    if (contextRetries < 3) {
        contextRetries++;
        // 强制压缩
        ContextCompactor.forceCompact(...);
        continue;  // 重试
    }
}
```

API 报错"上下文太长"时，不崩溃，而是强制触发压缩，然后 `continue` 重新跑这一轮。最多重试 3 次。

**② rate limit（限流）**

```java
if (lastErr.contains("rate limit")) {
    Thread.sleep(5000);
    continue;
}
```

遇到 rate limit，等 5 秒再重试。简单粗暴但有效。

**面试切入点**：生产级 Agent 必须处理这两类错误，否则一个长任务跑到一半直接崩掉。这就是 Harness Engineering 的体现 —— 在模型外围加确定性的错误恢复机制。

---

### 4.7 max_tokens 处理：Token 升级策略

```java
if ("max_tokens".equals(stopReason)) {
    if (!maxTokensEscalated) {
        maxTokensEscalated = true;
        client.setMaxOutputTokens(MAX_TOKENS_CEILING);  // 提升上限到 64000
        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped...");
        continue;  // 重试
    } else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
        outputRecoveries++;
        conv.addUserMessage("Output token limit hit. Resume ... Break remaining work into smaller pieces.");
        continue;
    }
}
```

LLM 输出被截断时的三级应对：
1. 第一次：把 `max_tokens` 上限从默认值提升到 64000，告诉模型"从截断处继续"
2. 第 2-3 次：继续追加续写指令，提示"把剩余工作拆细"
3. 第 4 次：放弃，当作正常完成处理

---

## 5. ConversationManager：对话历史是如何组织的

面试经常问：你的 Agent 是怎么管理对话历史的？

```java
public class ConversationManager {
    private final List<Message> history = new ArrayList<>();
    
    public void addUserMessage(String content) { ... }
    public void addAssistantFull(String text, List<ThinkingBlock> thinking, List<ToolUseBlock> toolUses) { ... }
    public void addToolResultsMessage(List<ToolResultBlock> results) { ... }
}
```

每一轮循环后，history 里的消息结构是这样的：

```
[0] user:      <system-reminder>（长期记忆、指令）
[1] user:      用户的问题
[2] assistant: 模型的回复 + ThinkingBlock + ToolUseBlock
[3] user:      工具执行结果（ToolResultBlock）
[4] assistant: 模型继续的回复...
...
```

**关键约束**：Anthropic API 要求 `tool_use` 和 `tool_result` 必须成对出现。即模型输出 `tool_use` 之后，下一条消息必须是包含对应 `tool_result` 的 user 消息。破坏这个配对会导致 API 报错（400）。这是上下文压缩时最难处理的地方（后续压缩模块会深入讲）。

---

## 6. AgentEvent：事件驱动的架构

```java
public sealed interface AgentEvent {
    record StreamText(String text) implements AgentEvent {}
    record ToolUseEvent(String toolId, String toolName, ...) implements AgentEvent {}
    record ToolResultEvent(...) implements AgentEvent {}
    record TurnComplete(int turn) implements AgentEvent {}
    record LoopComplete(int totalTurns) implements AgentEvent {}
    record ErrorEvent(String message) implements AgentEvent {}
    record CompactEvent(String message) implements AgentEvent {}
    record PermissionRequestEvent(...) implements AgentEvent {}
    ...
}
```

这是一个完整的事件系统，列举 Agent 可能产生的所有状态变化。

`sealed interface` + `record`：Java 17/21 的现代写法。`sealed` 保证子类型列举完整，`record` 保证数据不可变。这两个特性在面试中值得主动提及。

TUI 拿到这些事件后做渲染：
- `StreamText` → 实时打印文字
- `ToolUseEvent` → 显示"正在调用工具 XXX"
- `PermissionRequestEvent` → 弹出权限确认弹窗（future 用于异步等待用户确认）
- `LoopComplete` → 显示 token 用量、保存会话

---

## 7. 面试标准答法

**Q：你的 Agent Loop 整体流程是怎样的？**

> 每一轮进来先检查上下文长度，超阈值就触发压缩，保证不会因为历史太长让下一次 API 调用爆掉。然后把 system prompt、工具列表、对话历史、环境信息打包发给 LLM，开始流式接收，边收边把事件推给 UI 渲染。
>
> 流结束后判断模型是不是要调工具：
> - 不调就把回复落进历史，Loop 结束
> - 要调就把模型回复连同工具调用一起落历史，按并发安全性分批执行，工具结果塞回历史，进入下一轮
>
> 中间穿插着错误恢复：context too long 触发强制压缩重试，rate limit 等 5 秒重试，max_tokens 触发 token 上限升级。整个设计的核心是不让任何可预见的错误让 Loop 直接崩掉。

---

## 8. 知识扩展：ReAct 论文背景

Agent Loop 的学术名叫 **ReAct**（Reasoning + Acting），2022 年 Google 的论文提出。核心思想：让模型在"思考"（Reasoning）和"行动"（Acting，即工具调用）之间交替，每次行动的结果作为下一步思考的输入。

CodePal 的实现完全对应这个框架：
- Reasoning → LLM 的文字输出（包括 `<thinking>` 块）
- Acting → 工具调用执行
- Observation → 工具执行结果注入历史

如果面试官问"你了解 ReAct 框架吗"，可以说："了解，CodePal 的 Agent Loop 就是 ReAct 的工程实现，我们在 Java 21 上用 virtual thread + streaming + sealed event 把它做出来了。"

---

---

## 深挖问题记录

### Q1：Virtual Thread 的底层原理是什么？

**M:N 线程模型**：JVM 用少量 Carrier Thread（= CPU 核心数，跑在 OS 上）承载大量 Virtual Thread（可以有几百万个）。虚拟线程遇到 IO 阻塞时，JVM 自动把它的现场（栈帧）从 Carrier Thread 上卸下来存到堆内存（仅几 KB），Carrier Thread 立刻去跑其他虚拟线程，IO 完成后再重新挂上。

类比：服务员同时服务 100 桌，每桌等上菜的时候不是站那傻等，而是去处理其他桌。

Agent 场景 95% 时间在等 LLM IO，Virtual Thread 是完美匹配。

---

### Q2：BlockingQueue 背压机制的实现原理？

`LinkedBlockingQueue(64)` 内部是有界链表 + 两把锁（putLock / takeLock）+ 两个条件变量（notFull / notEmpty）。

- `put()`：队列满时，生产者在 `notFull` 上等待（阻塞），消费者取走一个元素后 signal。
- `take()`：队列空时，消费者在 `notEmpty` 上等待，生产者放入后 signal。
- 两把锁的原因：生产者操作尾部，消费者操作头部，互不冲突，分开加锁提高并发度（Doug Lea 的经典设计）。

`putSafe` 里 catch `InterruptedException` 后必须调 `Thread.currentThread().interrupt()` 恢复中断标记，否则上层的中断检查失效，用户 Ctrl+C 停不掉 Agent。

---

### Q3：tool_use / tool_result 配对约束是什么？为什么让压缩变难？

Anthropic API 硬性要求：assistant 消息里的每个 `tool_use`（带唯一 id），在紧接着的 user 消息里必须有对应 `tool_result`（相同 id），否则 API 返回 400。

CodePal 实现：所有工具结果收集到同一条 user 消息（`addToolResultsMessage`），保证 id 一一对应。

**让压缩变难**：压缩历史时不能随意截断，必须找到"合法切断点"——只能在完整的 tool_use/tool_result 对之后切，不能把一对拆开。ContextCompactor 最复杂的逻辑就在这里。

---

*下一模块：工具并发安全分批（StreamingExecutor.java）*
