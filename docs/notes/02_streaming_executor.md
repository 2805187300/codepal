# 模块二：工具并发安全分批

> 对应简历亮点：第 1 条 — 工具并发安全分批执行
> 关键文件：`src/main/java/com/codepal/agent/StreamingExecutor.java`、`src/main/java/com/codepal/tool/ToolCategory.java`

---

## 1. 问题背景

LLM 在一轮里可能同时返回多个工具调用：

```
[ReadFile(A), ReadFile(B), ReadFile(C), EditFile(A)]
```

- 全串行：安全，但 ReadFile 可以并发，白白浪费时间
- 全并发：高效，但 EditFile 与 ReadFile 同时跑会读到中间状态，数据损坏

CodePal 的解法：**相邻性分批** —— 连续 READ 工具合成并发批，WRITE/COMMAND 工具独占串行批。

---

## 2. ToolCategory：工具自声明并发属性

```java
public enum ToolCategory {
    READ,     // 只读，可并发
    WRITE,    // 写文件，必须串行
    COMMAND   // 执行命令，必须串行
}
```

每个工具自己声明类别，调用方代码不需要为新工具修改逻辑（开闭原则）。

| 工具 | Category | 原因 |
|------|----------|------|
| ReadFile / Glob / Grep | READ | 无副作用 |
| WriteFile / EditFile | WRITE | 修改文件 |
| Bash | COMMAND | 任意副作用 |

---

## 3. partitionToolCalls：相邻性分批算法

```java
for (var call : calls) {
    boolean safe = tool.category() == ToolCategory.READ;
    if (safe && !batches.isEmpty() && batches.getLast().concurrent()) {
        batches.getLast().calls().add(call);  // 合并进上一个并发批
    } else {
        batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));  // 开新批
    }
}
```

**执行示例：**

```
输入：[ReadFile(A), ReadFile(B), ReadFile(C), EditFile(A), ReadFile(D)]

批次 1（并发）：[ReadFile(A), ReadFile(B), ReadFile(C)]
批次 2（串行）：[EditFile(A)]
批次 3（并发）：[ReadFile(D)]
```

ReadFile(D) **不**和批次 1 合并，因为 EditFile(A) 在中间，D 需要看到 EditFile 修改后的状态。相邻性保证了执行语义与模型意图一致。

---

## 4. executeAll：批次调度

```java
if (batch.concurrent && batch.calls.size() > 1) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var futures = batch.calls.stream()
                .map(call -> executor.submit(() -> executeSingle(call)))
                .toList();
        for (var future : futures) { results.add(future.get()); }
    }  // try-with-resources：自动等待所有任务完成（批次间强等待）
} else {
    for (var call : batch.calls) results.add(executeSingle(call));
}
```

`try-with-resources` 关闭 ExecutorService 时自动等所有任务完成，实现批次间强等待。

---

## 5. executeSingle：单个工具完整执行链

```
[1] 权限检查（PermissionChecker）
    DENY → 返回错误
    ASK  → 弹窗等用户确认（CompletableFuture，最多 5 分钟）
    ALLOW → 继续
[2] Pre-tool Hook
[3] 执行工具 + 计时
[4] Recovery 快照（ReadFile 结果存入 recoveryState）
[5] 大结果落盘：超过 10000 字符 → 写磁盘，历史里只留路径引用
[6] 发送 ToolResultEvent 给 TUI
[7] Metrics 埋点（工具名、耗时、是否出错）
[8] Post-tool Hook
```

**权限 ASK 的异步等待设计：**

```java
var future = new CompletableFuture<PermissionResponse>();
putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
PermissionResponse response = future.get(5, TimeUnit.MINUTES);
```

Agent 线程发出携带 future 的事件，TUI 线程收到后显示弹窗，用户点击后 `future.complete(response)`，Agent 解除阻塞。两个线程只通过 future 通信，互不持有对方引用。

**大结果落盘（第一层上下文压缩入口）：**

```java
if (output.length() > 10_000) {
    path = ContextCompactor.writeSpill(...);
    output = "[Result of N chars saved to path]";  // 只留引用
}
```

防止单个大文件读取就撑爆上下文窗口。

---

## 6. 并发安全性证明

两个不变量：
1. 并发批内部全是 READ 工具，按定义不修改外部状态，多个 READ 并发不干扰
2. 批次间强等待，WRITE 执行前上一批全部完成，WRITE 执行后下一批才开始

结论：任意读写序列，分批执行与全串行执行结果等价。

---

## 7. 面试标准答法

**Q：工具并发是怎么设计的？**

> 每个工具自声明 READ/WRITE/COMMAND 类别。执行时用"相邻性分批"算法：连续 READ 合并为并发批，遇到 WRITE/COMMAND 就切开独占串行批，批次间强等待。并发批内用 Virtual Thread 并行执行。读写任意序列下语义与全串行一致，读密集场景效率显著提升。

**Q：spawn 子 Agent 为什么不能工具级并发？**

> spawn 有副作用（创建 worktree、注册 team 通道等共享状态），同时 spawn 会竞争。spawn 完后子 Agent 在后台自己并行跑，并发通过 background 模式实现。

---

*下一模块：两层渐进式上下文压缩（ContextCompactor.java）*
