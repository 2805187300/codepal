# 第8章：上下文管理、可观测性与评测驱动优化

> 目标：深入理解 CodePal 的两层上下文压缩机制、可观测性体系的设计，以及如何用评测驱动 Agent 的质量优化——这三者共同构成了"让 Agent 可信赖"的工程基础。
> 对应简历亮点：第3条（两层压缩）、第5条（评测驱动优化，17% → 100%）、第8条（可观测性体系）
> 关键文件：`compact/ContextCompactor.java`、`compact/RecoveryState.java`、`metrics/MetricsCollector.java`、`metrics/TraceStore.java`、`eval/EvalCase.java`、`eval/EvalRunner.java`

---

## 8.1 三个问题，一章解决

这一章围绕三个递进的问题展开：

1. **Agent 长任务跑着跑着就崩了**（上下文超限）→ 两层压缩解决
2. **怎么知道压缩后信息有没有丢？**（量化验证）→ 评测框架解决
3. **生产环境怎么知道 Agent 在哪个环节出了问题？**（可观测性）→ Metrics + Trace 解决

这三个问题不是独立的，它们形成一个闭环：压缩保证运行时稳定，评测验证压缩质量，可观测性支撑线上调优。

---

## 8.2 两层上下文压缩：架构概览

第3章已经深入讲过 `ContextCompactor` 的代码，这里从**工程决策视角**重新梳理，更适合面试时讲清楚来龙去脉。

### 为什么需要两层而不是一层？

单层压缩（只用 LLM 生成摘要）有两个问题：
1. **触发太晚**：等到接近上限才触发，但此时历史里可能有一条 10 万字符的工具结果，摘要请求本身就超长了
2. **触发太早**：为了防止上面的问题提前触发，但大部分时候历史还没那么大，白白调了一次 LLM（费时费钱）

两层的分工：
- **Layer 1**（轻量，每轮必跑）：专门处理"单条工具结果太大"的问题，纯本地操作，零 API 调用成本
- **Layer 2**（重量，按需触发）：处理"历史消息总量接近上限"的问题，需要调 LLM，有成本，所以按阈值触发

两层各司其职，Layer 1 持续清理大结果，让 Layer 2 触发时不会遇到"连摘要请求本身都超长"的极端情况。

### Layer 1 的触发阈值设计

```java
private static final int SINGLE_RESULT_LIMIT = 50_000;      // 单条超 5 万字符
private static final int MESSAGE_AGGREGATE_LIMIT = 200_000; // 一条消息里总和超 20 万字符
```

**为什么是 50000 而不是 10000？**

`StreamingExecutor` 里已经有一层 10000 字符的即时落盘（工具执行完立刻检查），所以到 `offloadAndSnip` 阶段时，刚刚执行的工具结果基本都已处理。这里的 50000 是处理**历史消息里已有的大结果**（比如上一次会话恢复进来的历史），阈值更高是因为这些结果已经在历史里了，不是"新鲜"的超大结果。

### Layer 2 的触发阈值设计

```java
// 有效窗口 = contextWindow − min(maxOutput, 20000)
// 软触发线 = 有效窗口 − 13000
// 硬触发线 = 有效窗口 − 3000
```

三个减法背后的逻辑：

**为什么要减 maxOutput（最多减 20000）？**
调 LLM 生成摘要本身要消耗输出 token，如果不预留，摘要请求发出去时已经没有空间让 LLM 输出摘要了。

**软触发线减 13000，硬触发线只减 3000？**
软触发是"有余量时主动压缩"，给自己留更多 buffer，压缩失败（断路器跳闸等）也不会立刻崩。硬触发是"不得不压了"，余量很小，但还有 3000 token 供 API 处理本次请求。

### 锚点机制：为什么字符估算不够准

```java
// 每次 LLM 返回后记录真实 token 数作为锚点
conv.recordUsageAnchor(turnInput, turnOutput, turnCacheRead, turnCacheCreation);

// 估算时：锚点真实值 + 锚点之后新增消息的字符估算
public static int currentTokens(List<Message> messages, UsageAnchor anchor) {
    List<Message> appended = messages.subList(anchor.anchorCount(), messages.size());
    return anchor.baselineTokens() + estimateTokens(appended);
}

// 纯字符估算：字符数 / 3.5
public static int estimateTokens(List<Message> messages) {
    total += (int)(safeLength(m.getContent()) / 3.5) + 4;
    // ...工具调用、工具结果、thinking block 各有系数
}
```

**Prompt Cache 命中时为什么字符估算会严重偏差？**

正常情况下，1000 字符 ≈ 285 token（字符/3.5）。但当 Prompt Cache 命中时，API 报告的 `input_tokens` 可能只有 50（因为大量前缀直接从缓存读取，不计为"新的 input token"）。纯字符估算会算出 285，真实消耗只有 50，高估了 5 倍。如果用高估值决定是否压缩，会提前触发压缩，浪费 API 调用。

锚点机制用真实 API 报告值作为基准，只对"锚点之后新增的消息"做字符估算，误差只在新增部分累积，整体精度高很多。

### 关键算法：computeKeepStartIndex 与配对保护

```java
static int computeKeepStartIndex(List<Message> messages) {
    int n = messages.size();
    int accumulated = 0, kept = 0;
    int keepStart = n;

    // 从最后一条消息往前走
    for (int i = n - 1; i >= 0; i--) {
        int msgTokens = estimateTokens(List.of(messages.get(i)));

        // 上限：这条加进来会超过 40000 token，停止
        if (accumulated + msgTokens > KEEP_MAX_TOKENS && kept > 0) break;
        accumulated += msgTokens;
        kept++;
        keepStart = i;

        // 下限：满足任意一个条件就停止往前走
        if (accumulated >= KEEP_RECENT_TOKENS || kept >= MIN_KEEP_MESSAGES) break;
    }

    // 配对保护：切断点不能落在 tool_result 消息上
    while (keepStart > 0 && isToolResultMessage(messages.get(keepStart))) {
        keepStart--;
    }
    return keepStart;
}
```

这里最容易被忽视但最重要的是最后那个 `while` 循环。

**为什么 tool_result 消息不能作为切断点的起始？**

假设历史是：
```
[5] assistant: text + tool_use(id="toolu_001")
[6] user:      tool_result(tool_use_id="toolu_001")   ← keepStart 落在这里
[7] assistant: 继续的回复
```

如果 keepStart = 6，压缩后的对话 = [摘要] + [6][7]。消息 [6] 是 tool_result，它的配对 tool_use 在 [5]，但 [5] 已经被压缩进摘要里了。这导致历史里有一条孤立的 tool_result，没有对应的 tool_use，Anthropic API 直接报 400。

`while` 循环把 keepStart 往前移，包含 [5]，保证 tool_use/tool_result 作为整体保留。

---

## 8.3 Recovery Attachment：压缩后的"工作记忆恢复"

压缩会让 LLM 忘记刚才读过哪些文件、正在执行哪个 Skill 的 SOP。Recovery Attachment 专门解决这个问题：

```java
// compact/RecoveryState.java — 持续记录两类快照
public class RecoveryState {

    // 最近读过的文件内容（以路径为 key，只保留最新版本）
    private final Map<String, FileReadRecord> files = new HashMap<>();

    // 最近激活过的 Skill 的 SOP 内容
    private final Map<String, SkillInvocationRecord> skills = new HashMap<>();

    public void recordFileRead(String path, String content) {
        synchronized (lock) {
            files.put(path, new FileReadRecord(path, content, Instant.now()));
        }
    }
}
```

**记录时机**：在 `StreamingExecutor.snapshotForRecovery` 里，每次 ReadFile 成功执行后：

```java
private void snapshotForRecovery(ToolCallInfo call, ToolResult result) {
    if (!"ReadFile".equals(call.toolName())) return;
    if (result.isError()) return;
    String path = (String) call.args().get("file_path");
    // 重新读磁盘文件，而不是用工具结果
    // 原因：工具结果带行号前缀，重新读能得到"干净"的文件内容
    String content = Files.readString(Path.of(path));
    recoveryState.recordFileRead(path, content);
}
```

**注意细节**：为什么不直接用 `result.output()` 而是重新 `Files.readString`？因为 ReadFileTool 的输出是带行号的（`"1\t第一行\n2\t第二行\n"`），如果原样存入 Recovery，LLM 会看到格式化过的内容，不是纯文件内容。重新读一次得到干净内容。

**压缩时追加**：

```java
// ContextCompactor.autoCompact 里
String content = summaryText;  // 摘要文字
String attachment = buildRecoveryAttachment(recovery, toolSchemas);
if (!attachment.isEmpty()) {
    content += "\n\n---\n\n" + attachment;
}
```

Recovery Attachment 包含四个部分（见第3章），附加在摘要消息末尾。压缩后的第一条消息就是"摘要 + 工作记忆快照"，LLM 继续工作时能立刻知道：刚才读了哪些文件、文件内容是什么、当前在执行哪个 Skill。

---

## 8.4 可观测性体系：MetricsCollector + TraceStore

**为什么 Agent 需要可观测性？**

传统应用出问题，你看日志、看异常栈就知道哪里出错了。Agent 出问题，你看对话历史，但对话历史可能有几十轮，每轮调了好几个工具，你怎么快速定位：
- 哪个工具最慢？（性能瓶颈）
- 哪个工具出错率最高？（可靠性问题）
- 每轮消耗多少 token？（成本分析）
- 这个任务跑了多少轮才完成？（效率分析）

MetricsCollector 和 TraceStore 就是为了回答这些问题。

**先建立行业术语：可观测性三支柱**

面试讲可观测性，一定要用行业标准术语来定位你的设计。可观测性（Observability）有公认的**三大支柱**：

| 支柱 | 是什么 | CodePal 的对应 |
|------|--------|--------------|
| **Metrics（指标）** | 聚合的数值统计，回答"整体怎么样"（成功率、P99 延迟、总量）| MetricsCollector |
| **Trace（追踪）** | 单次请求的完整链路，回答"这一次发生了什么"（每步耗时、调用顺序）| TraceStore（TurnTrace + ToolSpan）|
| **Log（日志）** | 原始事件流水，回答"具体细节是什么" | 对话历史 + 标准日志 |

这套术语来自分布式系统监控（OpenTelemetry 是事实标准）。Metrics 和 Trace 的关系是"总-分"：Metrics 告诉你"Bash 工具整体平均耗时 2.3 秒"（发现有问题），Trace 告诉你"Turn 5 那次 Bash 调用具体花了 12 秒、执行的是 `./gradlew build`"（定位到根因）。两者配合才能从"感觉慢"精确定位到"哪一步为什么慢"。面试时说"我们实现了可观测性三支柱中的 Metrics 和 Trace"，比说"我们有个统计模块"专业得多。

---

### MetricsCollector：全局聚合指标

```java
// metrics/MetricsCollector.java — 单例，全局唯一

public class MetricsCollector {

    private static final MetricsCollector INSTANCE = new MetricsCollector();

    // 工具调用总次数（线程安全：AtomicLong）
    private final AtomicLong totalToolCalls = new AtomicLong(0);
    private final AtomicLong toolErrors = new AtomicLong(0);

    // 按工具名分组统计（线程安全：ConcurrentHashMap）
    private final ConcurrentHashMap<String, AtomicLong> toolCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> toolErrorCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> toolTotalLatencyMs = new ConcurrentHashMap<>();

    // Token 消耗
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);

    // Agent Loop 轮次
    private final AtomicLong totalTurns = new AtomicLong(0);
```

**为什么用 `AtomicLong` 而不是普通 `long`？**

并发工具执行时（并发批次），多个工具同时向 MetricsCollector 写入数据。如果用普通 `long`，`totalToolCalls++` 在 JVM 里不是原子操作（读-加-写三步），多线程并发时会出现数据竞争，统计数据不准确。`AtomicLong` 的 `incrementAndGet()` 是一条原子指令（CAS），保证并发安全。

**什么是 CAS？**（面试常追问的底层原理）

CAS = Compare-And-Swap（比较并交换），是一条 CPU 硬件级的原子指令。它的逻辑是："我认为这个值现在是 A，如果确实是 A，就把它改成 B；如果不是 A（说明别人抢先改了），就失败重来。"这三步（比较+交换）由硬件保证不可分割。`AtomicLong.incrementAndGet()` 内部是一个 CAS 循环：读当前值 v，算出 v+1，CAS 尝试把 v 换成 v+1，失败就重读重试。相比 `synchronized` 加锁，CAS 是**无锁（lock-free）**的——线程不会被挂起阻塞，只是自旋重试，在竞争不激烈时性能远好于锁。这就是为什么高并发计数器都用 `AtomicLong` 而不是 `synchronized long`。

（延伸：竞争极激烈时 CAS 频繁失败重试反而浪费 CPU，Java 8 引入了 `LongAdder`——把计数分散到多个 cell 减少冲突，读时再汇总。如果 CodePal 的指标写入成为热点，可升级为 LongAdder。这个延伸能体现你懂得权衡。）

**`computeIfAbsent` 的惯用法：**

```java
public void recordToolCall(String toolName, long latencyMs, boolean isError) {
    totalToolCalls.incrementAndGet();
    // 如果 toolName 在 map 里不存在，创建新的 AtomicLong(0)，然后 +1
    toolCallCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
    toolTotalLatencyMs.computeIfAbsent(toolName, k -> new AtomicLong(0)).addAndGet(latencyMs);
    if (isError) {
        toolErrors.incrementAndGet();
        toolErrorCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
    }
}
```

`ConcurrentHashMap.computeIfAbsent` 是原子操作，即使两个线程同时发现 key 不存在，也只会创建一个 `AtomicLong`，不会有重复创建。

**格式化输出（`/metrics` 命令）：**

```java
public String formatReport() {
    // 运行时长
    long uptimeMs = System.currentTimeMillis() - startTimeMs;

    // 整体错误率
    double errorRate = totalCalls > 0 ? (totalErr * 100.0 / totalCalls) : 0.0;

    // 每个工具的统计（平均延迟 = 总延迟 / 调用次数）
    for (var entry : toolCallCounts.entrySet()) {
        String toolName = entry.getKey();
        long calls = entry.getValue().get();
        long errors = toolErrorCounts.getOrDefault(toolName, new AtomicLong(0)).get();
        long avgLatency = calls > 0 ? totalLatency / calls : 0;
        sb.append(String.format("  %-14s calls=%-5d errors=%-5d avg_latency=%dms", ...));
    }
}
```

输出示例：
```
=== CodePal Agent Metrics ===
Uptime: 12m 34s
Turns completed: 8

Token Usage:
  Input:  45,231
  Output: 3,892

Tool Calls: 23 total, 1 errors (4.3% error rate)

Per-Tool Stats:
  ReadFile       calls=12    errors=0     avg_latency=23ms
  EditFile       calls=4     errors=0     avg_latency=45ms
  Bash           calls=5     errors=1     avg_latency=2341ms
  Grep           calls=2     errors=0     avg_latency=18ms
```

从这份报告里立刻能看出：Bash 工具平均耗时 2341ms，远高于其他工具，可能是某个命令执行慢；Bash 有 1 次错误，可以去看对话历史定位是哪条命令失败了。

---

### TraceStore：轮次级别的时序追踪

MetricsCollector 是"聚合统计"，TraceStore 是"时序追踪"。两者的关系类似于监控系统里的 Metrics 和 Trace：Metrics 告诉你整体状况，Trace 告诉你某次具体操作的详情。

```java
// metrics/TraceStore.java

public record ToolSpan(
    String toolId,
    String toolName,
    long startMs,
    long endMs,
    boolean isError,
    String errorMsg
) {
    public long durationMs() { return endMs - startMs; }
}

public record TurnTrace(
    int turnNumber,
    long startMs,
    long endMs,
    List<ToolSpan> spans,    // 这一轮调了哪些工具，每个工具花了多久
    int inputTokens,
    int outputTokens
) {}
```

**Builder 模式构建 Trace：**

```java
// 开始一轮追踪
TurnTraceBuilder builder = traceStore.beginTurn(iteration);

// 每个工具执行前后打 Span
SpanBuilder span = builder.beginSpan(call.toolId, call.toolName);
ToolResult result = tool.execute(args);
span.end(result.isError(), result.isError() ? result.output() : null);

// 这轮结束，提交 Trace
builder.finish(turnInput, turnOutput);
```

**最多保留 100 条 Trace**：

```java
private static final int MAX_TRACES = 100;

public void addTrace(TurnTrace trace) {
    if (traces.size() >= MAX_TRACES) {
        traces.remove(0);  // 滚动窗口，删最老的
    }
    traces.add(trace);
}
```

`CopyOnWriteArrayList` 是线程安全的列表，读操作不需要加锁（复制一份副本读），写操作加锁。适合"读多写少"的场景——`getRecent` 频繁调用，`addTrace` 较少调用。

**格式化输出示例：**

```
Turn 1 (2.3s): 3 tool calls, 0 errors — [ReadFile 23ms, Grep 18ms, ReadFile 31ms]
Turn 2 (0.5s): 1 tool calls, 0 errors — [EditFile 45ms]
Turn 3 (4.1s): 2 tool calls, 1 errors — [Bash 2341ms, Bash 1800ms]
```

从这里能看出：Turn 3 有两次 Bash 调用，都耗时很长，其中一次出错了。配合 MetricsCollector 的汇总数据，可以快速定位问题。

---

## 8.5 评测驱动优化：从 17% 到 100% 的故事

这一节讲述整个项目里最有含金量的工程实践。

### 为什么要做评测？

在做 CodePal 之前，优化上下文压缩的方式是"凭感觉"：改一下 SUMMARY_SYSTEM_PROMPT，手动测几次，觉得好像好一点，就算改好了。

这样做有几个问题：
1. **不可量化**：怎么算"好"？好多少？没有数字
2. **覆盖不全**：手动测几次，很难覆盖所有场景
3. **退化检测**：改了一个地方，其他地方变差了，不知道

**评测系统的目标**：给出数字，让优化有依据，让退化可检测。

### EvalCase：一个测试用例是什么

```java
// eval/EvalCase.java
public record EvalCase(
    String id,                       // 用例 ID，如 "context-retention-001"
    String description,              // 描述，如 "信息保留率测试"
    String prompt,                   // 给 Agent 的初始提示词
    List<String> expectedFileEdits,  // 期望 Agent 修改了哪些文件
    List<String> requiredOutputKeywords, // Agent 输出里必须包含的关键词
    String successBashCheck          // 执行这条 Bash 命令来验证（exit code 0 = 通过）
) {}
```

**四种验证方式，覆盖不同类型的任务：**

| 验证方式 | 适用场景 | 示例 |
|---------|---------|------|
| `requiredOutputKeywords` | 验证 Agent 的回答是否包含关键信息 | `["保留率", "100%"]` |
| `expectedFileEdits` | 验证 Agent 是否创建/修改了文件 | `["/tmp/Hello.java"]` |
| `successBashCheck` | 运行命令验证更复杂的条件 | `"javac /tmp/Hello.java && java -cp /tmp Hello"` |
| 三者组合 | 复杂任务 | 文件存在 + 编译通过 + 输出包含关键词 |

**内置示例用例**：

```java
// "写一个 Hello World 程序" — 综合验证
new EvalCase(
    "hello-001",
    "Write hello world to a file",
    "Write a hello world Java program to /tmp/Hello.java",
    List.of("/tmp/Hello.java"),          // 验证文件存在
    List.of("Hello", "main"),            // 验证输出包含关键词
    "javac /tmp/Hello.java"              // 验证文件能编译通过
)
```

### EvalRunner：执行评测的引擎

```java
// eval/EvalRunner.java

private EvalResult runSingle(EvalCase evalCase) {
    long start = System.currentTimeMillis();

    // 1. 创建一个完全独立的 Agent 实例（不复用任何状态）
    LlmClient client = LlmClient.create(providerConfig, "You are a helpful coding assistant.");
    var conv = new ConversationManager();
    conv.addUserMessage(evalCase.prompt());
    Agent agent = new Agent(client, toolRegistry, providerConfig.getProtocol(), providerConfig);

    // 2. 收集 Agent 的所有输出
    var outputBuf = new StringBuilder();
    int[] tokens = {0, 0};
    var queue = new LinkedBlockingQueue<AgentEvent>(256);
    agent.run(conv, queue);

    // 3. 消费事件直到 LoopComplete 或超时（60 秒无响应视为 timeout）
    while (true) {
        AgentEvent ev = queue.poll(60, TimeUnit.SECONDS);
        if (ev == null) return fail(evalCase.id(), "Timeout", ...);
        if (ev instanceof AgentEvent.StreamText t) outputBuf.append(t.text());
        if (ev instanceof AgentEvent.UsageEvent u) { tokens[0] = u.inputTokens(); ... }
        if (ev instanceof AgentEvent.LoopComplete) break;
        if (ev instanceof AgentEvent.ErrorEvent e) return fail(evalCase.id(), e.message(), ...);
    }

    String output = outputBuf.toString();
    long duration = System.currentTimeMillis() - start;

    // 4. 依次检查验证条件
    // 4a. 关键词检查
    for (String kw : evalCase.requiredOutputKeywords()) {
        if (!output.contains(kw)) return fail(evalCase.id(), "keyword '" + kw + "' not found", ...);
    }

    // 4b. 文件存在检查
    for (String filePath : evalCase.expectedFileEdits()) {
        if (!Files.exists(Path.of(filePath))) return fail(evalCase.id(), "file not created: " + filePath, ...);
    }

    // 4c. Bash 命令验证（最强大，可验证任意条件）
    if (evalCase.successBashCheck() != null) {
        Process p = new ProcessBuilder("bash", "-c", evalCase.successBashCheck()).start();
        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        if (!done || p.exitValue() != 0) return fail(evalCase.id(), "bash check failed", ...);
    }

    return new EvalResult(evalCase.id(), true, null, duration, tokens[0], tokens[1], output);
}
```

### 专项测试：上下文信息保留率

这是简历亮点第5条的核心故事，代码里没有直接写，但通过 EvalCase + EvalRunner 就能实现：

```java
// 设计一个"信息保留率"测试用例（概念代码，不是实际文件）
new EvalCase(
    "context-retention-001",
    "上下文压缩信息保留率测试",

    // prompt：在开头注入 6 条关键信息
    """
    请记住以下 6 条重要信息：
    1. 项目名称是 MyProject
    2. 数据库密码是 secret123
    3. 主端口是 8080
    4. 部署环境是 production
    5. 负责人是 Alice
    6. 截止日期是 2025-12-31

    现在请开始执行以下任务（每个任务都调用工具，目的是撑满上下文触发压缩）：
    任务1：读取 README.md ...
    任务2：列出 src 目录下的文件 ...
    ... （重复多次，直到触发压缩）

    所有任务完成后，请回答：你还记得最开始说的那 6 条信息吗？逐条列出。
    """,

    List.of(),
    // 验证 Agent 的回答里还包含这 6 条信息的关键词
    List.of("MyProject", "secret123", "8080", "production", "Alice", "2025-12-31"),
    null
)
```

**第一次跑的结果**：只有 1/6 通过（`MyProject` 记住了，其他 5 条忘了），信息保留率 **17%**。

**定位问题**：查看 `SUMMARY_SYSTEM_PROMPT`，发现当时的版本只是：
```
"请总结以上对话内容"
```
太模糊，LLM 随意总结，把开头的关键信息当成不重要的内容省略了。

**修复**：改写成现在这个版本——分 9 章，明确要求保留用户的所有消息、所有关键数字、所有明确提到的任务要求：

```java
private static final String SUMMARY_SYSTEM_PROMPT = """
    Your task is to create a detailed summary...
    6. All user messages: List ALL user messages that are not tool results. These are critical...
    ...
""";
```

**这 9 章具体是什么？**（面试被追问"你到底怎么改的 prompt"，这是核心答案，不能只说"改成 9 章"）

结构化摘要 prompt 要求 LLM 按固定的 9 个章节输出，每章明确规定"必须保留什么类型的信息"。典型的 9 章结构如下：

| 章节 | 要求保留的信息 | 为什么关键 |
|------|--------------|-----------|
| 1. Primary Request & Intent | 用户最初的完整需求和意图 | 防止压缩后 Agent 忘了"到底要干嘛"（目标漂移） |
| 2. Key Technical Concepts | 涉及的关键技术概念、框架、约束 | 保留领域上下文 |
| 3. Files and Code Sections | 读过/改过的文件、关键代码片段 | 和 Recovery Attachment 呼应，保留工作状态 |
| 4. Errors and Fixes | 遇到的错误和对应的修复 | 防止重复踩同一个坑 |
| 5. Problem Solving | 已解决的问题和解决思路 | 保留决策链 |
| 6. **All User Messages** | **列出所有非工具结果的用户消息** | **最关键**——用户说的每句话都是意图信号，17%→100% 主要靠这条 |
| 7. Pending Tasks | 尚未完成的任务 | 防止漏做 |
| 8. Current Work | 当前正在做的事 | 压缩后能无缝接续 |
| 9. Next Step | 下一步该做什么 | 明确续接点 |

**为什么这样改就从 17% 到 100%？** 关键在**从"开放式"变成"清单式"**。原来的"请总结对话"是开放式的，LLM 有完全的自由裁量权，它会按自己认为的"重要性"取舍，把开头那些"记住这 6 个数字"当成不重要的背景省略掉。改成 9 章清单后，第 6 章白纸黑字要求"列出所有用户消息"——那 6 条关键信息就在用户消息里，LLM 没有省略它们的余地。**本质是用结构约束消除了 LLM 的随意性**。这也是 prompt engineering 的一条通用规律：要保证某类信息不丢，就给它一个专门的、强制的输出位置，而不是指望 LLM 自己判断重要性。

**修复后重跑**：6/6 全部通过，信息保留率 **100%**，**代码零改动**。

### 这个故事揭示的更深层道理

很多 Agent 的问题看起来是"代码 bug"，实际上是"prompt 问题"：
- 上下文压缩质量差 → 不是压缩算法有问题，是 SUMMARY_SYSTEM_PROMPT 写得不好
- 工具选错了 → 不是调度算法有问题，是工具的 description 写得不清楚
- 任务没做完就说完成了 → 不是 Agent Loop 有问题，是 System Prompt 没有明确"完成前要验证"

**评测是发现这类问题的唯一可靠方式**。凭感觉调 prompt 然后手动测，效率极低，很难发现隐蔽的退化。有了评测框架，改一次 prompt 就跑一次评测，数字立刻告诉你是进步了还是退步了。

---

## 8.6 生产中的使用：`/metrics` 命令

```java
// metrics/MetricsCommand.java
public class MetricsCommand {
    public static String handle() {
        StringBuilder sb = new StringBuilder();
        sb.append(MetricsCollector.getInstance().formatReport());
        sb.append("\n\n=== Recent Turn Traces ===\n");
        sb.append(TraceStore.getInstance().formatLastN(5));
        return sb.toString();
    }
}
```

用户在 TUI 里输入 `/metrics`，调用这个方法，返回完整的报告：上半部分是聚合统计，下半部分是最近 5 轮的时序追踪。

**实际调优场景举例：**

某次 Agent 跑一个大型重构任务，感觉比预期慢很多。输入 `/metrics`，看到：

```
Tool Calls: 45 total, 0 errors (0.0% error rate)

Per-Tool Stats:
  ReadFile       calls=20    errors=0     avg_latency=28ms
  EditFile       calls=8     errors=0     avg_latency=52ms
  Bash           calls=12    errors=0     avg_latency=3420ms  ← 这里异常
  Grep           calls=5     errors=0     avg_latency=15ms
```

Bash 工具平均耗时 3420ms，明显异常。查 Trace：

```
Turn 5 (38.2s): 3 tool calls, 0 errors — [Bash 12001ms, Bash 8934ms, Bash 9876ms]
```

Turn 5 里有 3 次 Bash 调用，每次 10 秒左右，因为这三个 Bash 是串行批次（有副作用），总耗时接近 30 秒。查对话历史，发现 LLM 在这一轮调了 3 次 `./gradlew build`，理论上只需要一次。问题定位到：System Prompt 里没有明确说"不要重复调用相同的构建命令"，LLM 每次修改文件后都重新构建验证。加上这条规则后，Turn 5 的耗时降到了 12 秒。

---

## 8.7 面试官可能问的问题

**Q：你们是怎么做上下文管理的？两层是怎么分工的？**

> 两层各解决一个问题。Layer 1 轻量，每轮必跑，处理"单条工具结果太大"：超过 5 万字符就落盘，历史里只留路径引用，代价是磁盘 IO，零 API 调用。Layer 2 重量，按 token 阈值触发，处理"历史消息总量接近上限"：用锚点+增量估算计算当前 token，接近软触发线时调 LLM 生成结构化摘要替换老旧历史，接近硬触发线时强制触发。压缩边界严格对齐 tool_use/tool_result 配对约束，防止 API 400。

**Q：评测框架是怎么设计的？**

> EvalCase 定义测试用例，包含 prompt、期望修改的文件列表、输出关键词、验证用的 Bash 命令四种验证方式。EvalRunner 创建独立的 Agent 实例运行用例，消费 AgentEvent 队列收集输出，依次执行四种验证，汇总成 EvalReport。最有价值的是自定义用例——我专门设计了"信息保留率"测试：开头注入 6 条关键信息，多轮任务撑满上下文触发压缩，最后验证 Agent 还记得几条。首次发现只有 17%，定位到 SUMMARY_SYSTEM_PROMPT 太简单，改写成 9 章结构化要求后重跑，100% 通过，代码零改动。

**Q：可观测性体系的设计是什么？**

> 两层：MetricsCollector 做聚合统计（工具调用次数、错误率、平均延迟、token 消耗），TraceStore 做时序追踪（每轮调了哪些工具、每个工具耗时多少）。都用单例模式，并发写用 AtomicLong 和 ConcurrentHashMap 保证线程安全。TraceStore 用滚动窗口保留最近 100 条，用 CopyOnWriteArrayList 实现读不加锁。用户输入 `/metrics` 命令可以实时查看全量报告，帮助定位性能瓶颈和错误热点。对应可观测性三支柱里的 Metrics 和 Trace。

**Q（追问）：压缩的配对保护，如果切断点落在 tool_use（而不是 tool_result）上会怎样？要不要也处理？**

> 不用特殊处理，而且这种情况本身就是安全的。想清楚方向：keepStart 是"从这条开始保留"，被压缩进摘要的是 keepStart **之前**的消息。
> - 如果切断点落在 **tool_result** 上（8.2 讲的情况）：它的配对 tool_use 在前面、被压进摘要了，于是保留区里出现一条"孤儿 tool_result"——API 报 400。所以要把 keepStart 往前挪，把 tool_use 也纳入保留区。
> - 如果切断点落在 **tool_use** 上：它的配对 tool_result 在它后面（也在保留区里），tool_use 和 tool_result 都被完整保留，配对没断。而被压进摘要的是这条 tool_use 之前的完整对话，摘要里不涉及未配对的调用。所以天然安全，不需要处理。
>
> 一句话：危险的只有"tool_result 被留下但它的 tool_use 被压走"这一个方向，反方向不会出问题。这说明保护逻辑不是无脑对称的，是想清楚了配对的时序方向才写的。

**Q（追问）：压缩会不会把还需要的信息也压掉？你怎么评估压缩的信息损失？**

> 会，这正是 8.5 那个信息保留率评测要解决的。评估方法：设计一个 EvalCase，开头注入 N 条关键信息，用多轮工具调用撑满上下文强制触发压缩，最后让 Agent 复述那 N 条，通过率就是信息保留率。这把"压缩质量"这个模糊的东西变成了可量化、可回归的数字。我们靠它发现摘要 prompt 的问题、从 17% 优化到 100%。核心观点：**压缩必然有损，关键是让"损失"可测量、可控制**，而不是假装没损失。

**Q（追问）：评测的 successBashCheck 怎么防止 Agent"作弊"？比如它直接 echo 关键词骗过关键词检查？**

> 这是评测有效性的核心问题。关键词检查（`requiredOutputKeywords`）确实容易被"作弊"——Agent 只要在输出里 echo 那几个词就能过，不代表它真做对了。所以：
> - 关键词检查只适合验证"信息保留"这类**回答类**任务（要的就是它说出那几个词）。
> - 验证"任务完成"要用 **successBashCheck** 查**真实产物**：不是看 Agent 说"我写好了 Hello.java 并且能编译"，而是真的去 `javac /tmp/Hello.java` 看 exit code。Agent 没法 echo 骗过一次真实编译。
> - 更强的做法是验证**行为效果而非声明**：让 Agent 说什么不重要，看它有没有真的改对文件、测试有没有真的从红变绿。
>
> 原则：**验证要查客观事实（文件、编译、测试），不能查 Agent 的自我声明**——因为 Agent 有动机（也有能力）说"我做完了"来结束任务。这跟 SWE-bench 用真实测试套件判定通过是一个道理。

---

## 8.8 生产中可能遇到的问题

**问题1：压缩摘要质量不稳定，有时候丢失重要信息**

根本原因：SUMMARY_SYSTEM_PROMPT 虽然写了 9 章，但某些任务的关键信息特别多，LLM 生成摘要时会做取舍，不是所有信息都能保留。

解法：
1. 增加 `KEEP_RECENT_TOKENS` 和 `MIN_KEEP_MESSAGES`，保留更多原文
2. 对关键类型的信息（比如用户明确说的"记住 xxx"）在 System Prompt 里单独强调要保留
3. 在评测集里加入更多此类场景的用例，量化追踪

**问题2：MetricsCollector 的数据在进程重启后丢失**

MetricsCollector 是内存里的单例，进程重启清空。对于需要跨会话分析的场景，需要定期把指标写入文件或外部系统（如 Prometheus、InfluxDB）。

**问题3：EvalRunner 的测试用例之间有状态污染**

每个测试用例创建独立的 `Agent` 和 `ConversationManager`，但如果测试用例修改了文件系统（`expectedFileEdits`），上一个用例留下的文件可能影响下一个。

解法：每个用例执行前后做好清理：
```java
// 用例执行前
Files.deleteIfExists(Path.of("/tmp/Hello.java"));
// 用例执行后（teardown）
// 或者用临时目录，测完自动删除
```

**问题4：上下文压缩时同时有并发工具在运行**

压缩（`ContextCompactor.autoCompact`）和工具执行都在 Agent 线程里，是串行的——压缩完了才执行工具，执行完工具才可能触发压缩。不存在并发冲突。

但如果将来改成异步压缩（在工具执行期间后台压缩），就需要加锁保护 `conv` 对象。ConversationManager 目前不是线程安全的，直接改异步会有 race condition。

**问题5（用户视角）：压缩发生时，用户感觉 Agent 突然"变笨了""忘事了"**

压缩是自动触发的，用户往往无感知。但压缩后 Agent 可能对压缩前的某个细节回答得含糊了，用户会觉得"你怎么把刚才说的忘了"，体验上像 Agent 突然降智。

解法：一是压缩发生时给用户一个**可见提示**（"上下文已压缩以继续长任务"），让用户理解为什么 Agent 的"记忆"发生了变化，而不是莫名其妙；二是 Recovery Attachment（8.3）尽量保住工作状态，减少可感知的信息丢失；三是关键信息靠 9 章摘要结构强制保留。透明地告知用户"发生了压缩"，比让用户自己猜"它怎么了"要好得多。

**问题6（用户视角）：`/metrics` 的数字用户看不懂、不知道该怎么用**

`avg_latency=2341ms`、`error rate 4.3%` 这些数字对开发者有意义，但普通用户看了不知道是好是坏、该做什么。可观测性数据如果只是罗列，对用户价值有限。

解法：在报告里加**解读和建议**——不只是显示"Bash avg_latency=3420ms"，而是标注"⚠ Bash 工具偏慢，可能有命令重复执行，可查看 Turn 5 的 Trace"。把原始指标翻译成用户能理解、能行动的信息。可观测性的终点是"能指导行动"，不是"把数字堆出来"。

---

*下一章：记忆系统 — 跨会话的 Agent 长期记忆*
