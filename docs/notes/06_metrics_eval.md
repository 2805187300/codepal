# 模块六：可观测性体系 + 评测驱动优化

> 对应简历亮点：第 5 条（评测驱动，17%→100%）、第 8 条（可观测性体系）
> 关键文件：`metrics/MetricsCollector.java`、`metrics/TraceStore.java`、`eval/EvalCase.java`、`eval/EvalRunner.java`

---

## 1. 可观测性：MetricsCollector + TraceStore

### 为什么需要可观测性？

Agent 出问题不像传统应用能直接看异常栈。你需要知道：哪个工具最慢？哪个出错率最高？每轮消耗多少 token？这轮为什么卡住了？

### 两层覆盖可观测性三支柱中的 Metrics 和 Trace

| 支柱 | CodePal 实现 | 回答什么问题 |
|------|-------------|------------|
| Metrics（指标）| MetricsCollector | 整体状况：平均延迟、错误率、总 Token |
| Trace（追踪）| TraceStore | 单次详情：Turn 5 那次 Bash 具体跑了多久 |

### MetricsCollector 关键设计

```java
// 单例，并发安全
private final AtomicLong totalToolCalls = new AtomicLong(0);
private final ConcurrentHashMap<String, AtomicLong> toolCallCounts = new ConcurrentHashMap<>();
```

用 `AtomicLong`（CAS 原子操作）和 `ConcurrentHashMap` 保证并发批次同时写入不出数据竞争，比 `synchronized` 性能好。`computeIfAbsent` 原子地初始化新工具的计数器。

### TraceStore 关键设计

```java
record ToolSpan(String toolName, long startMs, long endMs, boolean isError) {}
record TurnTrace(int turnNumber, List<ToolSpan> spans, int inputTokens, int outputTokens) {}
```

最多保留 100 条（滚动窗口），用 `CopyOnWriteArrayList`（读不加锁，适合读多写少）。用 Builder 模式在工具执行前后打 Span。

### 使用效果

输入 `/metrics` 立即看到：
```
Bash  calls=12  errors=1  avg_latency=3420ms  ← 异常，去查 Trace
Turn 5 (38s): [Bash 12001ms, Bash 8934ms, Bash 9876ms]  ← 定位到 3 次重复构建
```

---

## 2. 评测驱动优化：17% → 100% 的故事

### 为什么要做评测？

优化 SUMMARY_SYSTEM_PROMPT 如果靠"感觉"——改一下手动测几次，效率极低，也检测不到退化。

### EvalCase：测试用例结构

```java
record EvalCase(
    String id,
    String prompt,
    List<String> expectedFileEdits,       // 验证文件存在
    List<String> requiredOutputKeywords,   // 验证输出包含关键词
    String successBashCheck               // 运行命令验证（最强，防作弊）
) {}
```

四种验证方式组合使用。`successBashCheck` 最可靠——`javac Hello.java` exit code 0 才算通过，Agent 没法靠 echo 骗过真实编译。

### 专项测试设计（信息保留率）

```
prompt：注入 6 条关键信息 → 多轮工具调用撑满上下文触发压缩 → 问 Agent 还记得几条
requiredOutputKeywords：["MyProject", "8080", "production", "Alice", ...]
```

**第一次跑：1/6 通过（17%）**

定位：`SUMMARY_SYSTEM_PROMPT` 只写"请总结对话"，LLM 自由裁量把开头关键信息当背景省略了。

**修复：改写为 9 章结构化 prompt**

| 章节 | 关键约束 |
|------|---------|
| 1. Primary Request | 用户原始意图 |
| 6. **All User Messages** | **列出所有用户消息**（这是核心修复点）|
| 7. Pending Tasks | 未完成任务 |
| ... | ... |

第 6 章白纸黑字要求"列出所有用户消息"，那 6 条关键信息就在用户消息里，LLM 没有省略的余地。**用结构约束消除了 LLM 的随意性**。

**修复后重跑：6/6 通过（100%），代码零改动**

### 更深的启示

很多 Agent 问题本质是 prompt 问题，不是代码问题：
- 压缩质量差 → SUMMARY_SYSTEM_PROMPT 写得不好
- 工具选错 → 工具 description 不清楚
- 任务没完成就说完了 → System Prompt 没要求"完成前验证"

**评测是发现这类问题的唯一可靠手段。**

---

## 3. 面试标准答法

**Q：可观测性体系是怎么设计的？**

> MetricsCollector 做聚合统计（工具调用次数、错误率、平均延迟、Token 消耗），TraceStore 做轮次级时序追踪（每轮调了哪些工具、各耗时多少）。并发写用 AtomicLong+ConcurrentHashMap 保证线程安全。TraceStore 滚动窗口保留最近 100 条。`/metrics` 命令实时查看，对应可观测性三支柱里的 Metrics 和 Trace。

**Q：评测框架是怎么设计的？**

> EvalCase 定义测试用例：prompt、期望文件、输出关键词、Bash 验证命令四种验证方式。EvalRunner 创建独立 Agent 实例运行，消费 AgentEvent 队列，依次执行四种验证。我设计了"信息保留率"专项测试：注入 6 条关键信息，撑满上下文触发压缩，验证还记得几条。首次发现 17%，定位到 SUMMARY_SYSTEM_PROMPT 缺陷，改写为 9 章结构化 prompt 后 100% 通过，代码零改动。

**Q：17% → 100% 你到底改了什么？**

> 原来 prompt 是开放式的"请总结对话"，LLM 可以自由取舍。改成 9 章结构化 prompt，其中第 6 章强制要求"列出所有用户消息"，关键信息就在用户消息里，LLM 没有省略空间。本质是用结构约束消除了 LLM 的随意性。

---

*下一模块：多 Agent 并行协作（TeamManager.java、TeammateRunner.java）*
