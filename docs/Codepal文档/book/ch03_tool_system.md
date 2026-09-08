# 第3章：工具系统

> 目标：理解 Agent 的"手"是怎么设计的，一个工具从定义到执行的完整生命周期，以及并发安全分批执行的设计原理。
> 关键文件：`tool/Tool.java`、`tool/ToolCategory.java`、`tool/ToolResult.java`、`tool/ToolRegistry.java`、`tool/impl/` 下各工具、`agent/StreamingExecutor.java`

---

## 3.1 什么是工具（Tool）

在 Agent 的语境里，**工具就是 Agent 能做的事**。

LLM 本身只能输出文字，它没有"手"——不能读文件、不能跑命令、不能改代码。**工具**是 Agent 给 LLM 配的"手"。

Function Calling（也叫 Tool Use）是 OpenAI/Anthropic 提供的协议，允许你告诉 LLM："这里有一批工具，每个工具有名字、描述、参数说明。如果你需要，可以请求调用某个工具，我来执行并把结果告诉你。"

流程如下：
```
你 → 发消息给 LLM + 工具列表
LLM → 返回：我要调用 ReadFile(file_path="/src/Agent.java")
你 → 执行 ReadFile，读出文件内容
你 → 把结果回传给 LLM
LLM → 根据文件内容继续回复
```

这个"你"就是 Agent 框架，在 CodePal 里是 `StreamingExecutor`。

---

## 3.2 Tool 接口：所有工具的契约

```java
// tool/Tool.java
public interface Tool {

    String name();           // 工具名，LLM 调用时用这个名字

    String description();    // 工具描述，LLM 看这个决定要不要调用

    ToolCategory category(); // 并发分类：READ / WRITE / COMMAND

    Map<String, Object> schema();  // JSON Schema，告诉 LLM 参数格式

    ToolResult execute(Map<String, Object> args);  // 执行逻辑

    default boolean shouldDefer() { return false; }  // 是否延迟加载（MCP 工具用）
}
```

五个方法，分工明确：

- `name()` + `description()` + `schema()`：这三个是给 LLM 看的，决定 LLM 怎么调用这个工具
- `category()`：给框架看的，决定并发调度策略
- `execute()`：真正干活的地方

**ToolResult 的设计：**

```java
// tool/ToolResult.java
public record ToolResult(String output, boolean isError) {
    public static ToolResult success(String output) { return new ToolResult(output, false); }
    public static ToolResult error(String message)  { return new ToolResult(message, true); }
}
```

用 `isError` 标记区分成功和失败，而不是抛异常。原因：工具执行失败时，Agent 不应该崩溃，而是把错误信息回传给 LLM，让 LLM 知道"这步没成功，换个方式"。

---

## 3.3 schema()：工具的"使用说明书"

schema 是工具能力向 LLM 暴露的唯一界面。LLM 通过读 schema 理解这个工具的参数格式，然后生成符合格式的调用请求。

以 `ReadFileTool` 为例：

```java
@Override
public Map<String, Object> schema() {
    return Map.of(
        "name", "ReadFile",
        "description", "Read a file and return its contents with line numbers...",
        "input_schema", Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of("type", "string", "description", "Absolute or relative path to the file"),
                "offset",    Map.of("type", "integer", "description", "Line offset to start reading from", "default", 0),
                "limit",     Map.of("type", "integer", "description", "Maximum number of lines to read", "default", 2000)
            ),
            "required", List.of("file_path")
        )
    );
}
```

转成 JSON 之后发给 Anthropic API，大概长这样：

```json
{
  "name": "ReadFile",
  "description": "Read a file and return its contents with line numbers...",
  "input_schema": {
    "type": "object",
    "properties": {
      "file_path": {"type": "string", "description": "..."},
      "offset":    {"type": "integer", "default": 0},
      "limit":     {"type": "integer", "default": 2000}
    },
    "required": ["file_path"]
  }
}
```

LLM 看到这个，就知道：调用 ReadFile 时必须传 `file_path`，可选传 `offset` 和 `limit`。

**description 的质量极其重要**。LLM 靠 description 来决定：
1. 什么时候该用这个工具（而不是别的工具）
2. 怎么填参数

description 写得模糊，LLM 可能乱调工具；description 写得清楚，LLM 就能精准调用。

---

## 3.4 三种工具的实现细节

### ReadFileTool：只读操作的典范

```java
@Override
public ToolResult execute(Map<String, Object> args) {
    String filePath = stringArg(args, "file_path", "");
    int offset = intArg(args, "offset", 0);
    int limit  = intArg(args, "limit", 2000);

    Path path = Path.of(filePath);

    // 防御性检查
    if (!Files.exists(path)) return ToolResult.error("Error: file not found: " + filePath);
    if (Files.isDirectory(path)) return ToolResult.error("Error: not a file: " + filePath);

    String content = Files.readString(path);
    String[] lines = content.split("\n", -1);

    // 加行号输出：每行前缀 "行号\t内容"
    var sb = new StringBuilder();
    for (int i = offset; i < Math.min(offset + limit, lines.length); i++) {
        if (i > offset) sb.append('\n');
        sb.append(i + 1).append('\t').append(lines[i]);
    }

    // 记录进 FileStateCache，供 EditFileTool 验证"必须先读再改"
    if (fileStateCache != null) {
        long mtime = Files.getLastModifiedTime(path).toMillis();
        fileStateCache.record(path.toAbsolutePath().toString(), mtime);
    }

    return ToolResult.success(sb.toString());
}
```

**三个设计点：**

① **带行号输出**：LLM 看到行号后，在调用 EditFileTool 时可以精确引用某行，减少歧义。

② **offset + limit 分页**：大文件不一次性读完，避免单次结果撑爆上下文。LLM 如果需要后半部分，再发一次 ReadFile 请求，带上 `offset`。

③ **FileStateCache 记录**：读过的文件会记录修改时间。EditFileTool 执行时会检查：如果没有读过这个文件，拒绝编辑。这防止了"还没看就乱改"的危险操作。

---

### EditFileTool：精确字符串替换

```java
@Override
public ToolResult execute(Map<String, Object> args) {
    String filePath = stringArg(args, "file_path", "");
    String oldStr   = stringArg(args, "old_string", "");
    String newStr   = stringArg(args, "new_string", "");

    // 必须先 ReadFile 才能编辑
    if (fileStateCache != null) {
        String err = fileStateCache.validate(path.toAbsolutePath().toString());
        if (err != null) return ToolResult.error(err);
    }

    String content = Files.readString(path);

    // 唯一性检查：old_string 必须在文件中恰好出现一次
    int count = countOccurrences(content, oldStr);
    if (count == 0) return ToolResult.error("Error: old_string not found in file");
    if (count > 1)  return ToolResult.error("Error: old_string found " + count + " times, must be unique");

    String newContent = content.replace(oldStr, newStr);

    // 原子写：先写临时文件，再 atomic move，防止写到一半崩溃导致文件损坏
    Path tempFile = Files.createTempFile(path.getParent(), ".codepal-edit-", ".tmp");
    Files.writeString(tempFile, newContent);
    Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    // 返回 diff，让 LLM 和 TUI 都知道具体改了什么
    DiffUtil.DiffResult diff = DiffUtil.buildDiff(content, newContent);
    return ToolResult.success("Updated " + filePath + " with " + diff.additions() + " additions...\n" + diff.text());
}
```

**四个设计点：**

① **唯一性检查**：`old_string` 必须恰好出现一次。如果出现多次，LLM 不知道要改哪一个，报错让 LLM 提供更多上下文。这是 Claude Code 的一个核心设计哲学：工具宁可报错让模型重试，也不做可能出错的操作。

② **先读再改强制要求**：`FileStateCache.validate()` 检查是否读过这个文件。没读就改，LLM 可能在操作一个它"想象"中的文件版本，与磁盘实际内容不符，导致错误的 edit。

③ **原子写（Atomic Move）**：先写临时文件 `.codepal-edit-xxx.tmp`，写完后原子性地重命名替换目标文件。如果写到一半进程崩溃，原文件完好无损，只有一个孤立的 tmp 文件。这是写文件的生产级标准操作。

④ **返回 diff**：不只说"改好了"，而是返回具体的增删行信息。LLM 收到 diff 后能验证修改是否符合预期，不对可以立刻纠正。

---

### BashTool：危险但强大

```java
@Override
public ToolResult execute(Map<String, Object> args) {
    String command = stringArg(args, "command", "");
    int timeout = Math.min(intArg(args, "timeout", 120), MAX_TIMEOUT);  // 最长 600 秒

    // 如果配置了 OS 级沙箱，命令先通过沙箱包装
    String actualCommand = command;
    if (sandbox != null && sandbox.isAvailable() && sandboxConfig != null) {
        actualCommand = sandbox.wrap(command, sandboxConfig);
    }

    ProcessBuilder pb = new ProcessBuilder("bash", "-c", actualCommand);
    pb.redirectErrorStream(true);  // 合并 stdout 和 stderr
    Process process = pb.start();

    String output = new String(process.getInputStream().readAllBytes());

    boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
    if (!finished) {
        process.destroyForcibly();
        return ToolResult.error("Error: command timed out after " + timeout + "s");
    }

    int exitCode = process.exitValue();
    // 非零 exit code 不一定是错误（grep 没匹配到返回 1 是正常的）
    // 附加语义提示，isError 始终为 false
    return new ToolResult(output + exitCodeInfo, false);
}
```

**四个设计点：**

① **stderr 合并**：`redirectErrorStream(true)` 把 stdout 和 stderr 合并到一个流。命令执行失败时错误信息通常在 stderr，合并后 LLM 能同时看到两者，不会遗漏关键错误信息。

② **超时保护**：`process.waitFor(timeout, TimeUnit.SECONDS)`，超时后 `destroyForcibly()` 强杀进程。没有超时保护的话，一个死循环命令（如 `sleep infinity`）会卡死整个 Agent。

③ **exit code 不等于 isError**：这是个很精妙的设计。`grep` 没找到匹配时返回 exit code 1，这是正常行为，不是错误。如果把所有非零 exit code 都当 isError，LLM 会错误地认为"grep 执行失败"。CodePal 对 grep/diff/find/test 等命令的 exit code 1 附加语义提示，让 LLM 正确理解。

④ **OS 级沙箱**：可选接入 macOS 的 `sandbox-exec`（seatbelt）或 Linux 的 `bwrap`，在内核级隔离命令执行环境，防止越权操作（第6章详细讲）。

---

## 3.5 ToolRegistry：工具的"注册中心"

```java
// tool/ToolRegistry.java
public class ToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Set<String> discoveredTools = ConcurrentHashMap.newKeySet();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Map<String, Object>> getAllSchemas(String protocol) {
        // 遍历所有工具，返回它们的 schema
        // 延迟加载（shouldDefer）的工具，未被 discover 时跳过
        for (var tool : tools.values()) {
            if (tool.shouldDefer() && !discoveredTools.contains(tool.name())) continue;
            schemas.add(convertSchema(tool.schema(), protocol));
        }
        return schemas;
    }
}
```

**为什么用 ConcurrentHashMap？**

ToolRegistry 会在多线程环境下访问：
- 主 Agent 线程读工具列表（`getAllSchemas`）
- 并发执行的工具线程执行工具（`get`）
- MCP 初始化线程注册新工具（`register`）

`ConcurrentHashMap` 是线程安全的，不会在并发读写时产生数据竞争。

**协议适配（Anthropic vs OpenAI 格式）：**

```java
if (isOpenAIProtocol(protocol)) {
    schemas.add(Map.of(
        "type", "function",
        "name", base.get("name"),
        "description", base.get("description"),
        "parameters", base.get("input_schema")    // ← OpenAI 用 "parameters"
    ));
} else {
    schemas.add(base);   // Anthropic 用 "input_schema"
}
```

Anthropic 和 OpenAI 的工具 schema 字段名不同（`input_schema` vs `parameters`），ToolRegistry 在返回 schema 时自动转换，工具本身不需要关心协议差异。

---

## 3.6 工具并发安全分批：ToolCategory 的作用

这是简历亮点第1条的核心，前面章节笔记里讲过原理，这里结合代码再过一遍。

### ToolCategory 枚举

```java
public enum ToolCategory {
    READ,     // 纯读操作：ReadFile、Glob、Grep
    WRITE,    // 写文件：EditFile、WriteFile
    COMMAND   // 执行命令：Bash（可能有任意副作用）
}
```

每个工具在 `category()` 方法里自声明自己的类别：

```java
// ReadFileTool
public ToolCategory category() { return ToolCategory.READ; }

// EditFileTool
public ToolCategory category() { return ToolCategory.WRITE; }

// BashTool
public ToolCategory category() { return ToolCategory.COMMAND; }
```

### StreamingExecutor：分批调度

```java
// agent/StreamingExecutor.java

// 第一步：按相邻性分批
private List<ToolBatch> partitionToolCalls(List<ToolCallInfo> calls) {
    var batches = new ArrayList<ToolBatch>();
    for (var call : calls) {
        boolean safe = registry.get(call.toolName()).category() == ToolCategory.READ;
        if (safe && !batches.isEmpty() && batches.getLast().concurrent()) {
            batches.getLast().calls().add(call);  // 合并进当前并发批
        } else {
            batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));  // 开新批
        }
    }
    return batches;
}

// 第二步：逐批执行
public List<ToolExecResult> executeAll(List<ToolCallInfo> calls) {
    var batches = partitionToolCalls(calls);
    var results = new ArrayList<ToolExecResult>();

    for (var batch : batches) {
        if (batch.concurrent() && batch.calls().size() > 1) {
            // 并发批：用 Virtual Thread 并行执行，try-with-resources 等所有完成
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = batch.calls().stream()
                        .map(call -> executor.submit(() -> executeSingle(call)))
                        .toList();
                for (var future : futures) results.add(future.get());
            }
        } else {
            // 串行批：一个一个执行
            for (var call : batch.calls()) results.add(executeSingle(call));
        }
    }
    return results;
}
```

**图解一个完整例子：**

```
模型一轮返回：[ReadFile(A), ReadFile(B), Grep(C), EditFile(A), ReadFile(D)]

分批结果：
  批次 1（concurrent=true）：[ReadFile(A), ReadFile(B), Grep(C)]
    → 三个同时跑（Virtual Thread 并发），耗时 = max(A耗时, B耗时, C耗时)
  批次 2（concurrent=false）：[EditFile(A)]
    → 等批次1全部完成后，串行执行
  批次 3（concurrent=true）：[ReadFile(D)]
    → 等批次2完成后执行（D 要看到 EditFile 之后的状态）

总耗时 ≈ max(读ABC) + EditA + 读D
串行耗时 = 读A + 读B + 读C + EditA + 读D
```

读密集场景下，并发批大幅缩短总耗时。

### 为什么 ReadFile(D) 不能和 ReadFile(A/B/C) 合并？

因为它们中间隔着 `EditFile(A)`。D 需要在 A 被修改**之后**读取，才能看到修改后的内容。如果提前到批次 1 并发，D 读到的是旧文件。

**"相邻性"分批的精妙之处**：不是把所有 READ 工具都合并，而是只合并中间没有 WRITE 隔断的 READ 工具。这保证了执行顺序与模型意图完全一致。

---

## 3.7 单个工具执行的完整生命周期

```java
private ToolExecResult executeSingle(ToolCallInfo call) {

    // ① 工具存在性检查
    Tool tool = registry.get(call.toolName());
    if (tool == null) return error("Unknown tool: " + call.toolName());

    // ② 权限检查（第6章详细讲）
    if (checker != null) {
        var check = checker.check(tool, call.args());
        switch (check.decision()) {
            case DENY  → return error("Permission denied");
            case ASK   → {
                // 弹权限确认窗，用 CompletableFuture 等用户点击
                var future = new CompletableFuture<PermissionResponse>();
                putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
                PermissionResponse response = future.get(5, TimeUnit.MINUTES);
                if (response == DENY) return error("User denied permission");
            }
            case ALLOW → {} // 放行
        }
    }

    // ③ Pre-tool Hook（第12章详细讲）
    if (hookEngine != null) {
        var hookResult = hookEngine.runPreToolHooks(call.toolName(), call.args());
        if (hookResult.rejected()) return error("Rejected by hook: " + hookResult.message());
    }

    // ④ 执行工具，计时
    long start = System.nanoTime();
    ToolResult result;
    try {
        result = tool.execute(call.args());
    } catch (Exception e) {
        result = ToolResult.error("Tool execution error: " + e.getMessage());
    }
    double elapsed = (System.nanoTime() - start) / 1e9;

    // ⑤ Recovery 快照（ReadFile 结果存入 recoveryState，压缩时用）
    snapshotForRecovery(call, result);

    // ⑥ 大结果落盘（第8章详细讲）
    if (output.length() > ToolRegistry.MAX_OUTPUT_CHARS) {
        path = ContextCompactor.writeSpill(...);
        output = "[Result of N chars saved to path]";
    }

    // ⑦ 把结果事件推给 TUI
    putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), output, result.isError(), elapsed));

    // ⑧ Metrics 埋点（第可观测性章节讲）
    MetricsCollector.getInstance().recordToolCall(call.toolName(), elapsedMs, result.isError());

    // ⑨ Post-tool Hook
    if (hookEngine != null) hookEngine.runHooks(POST_TOOL_USE ctx);

    return new ToolExecResult(call.toolId(), output, result.isError());
}
```

9 个步骤，每一步都有明确职责。这是"单一职责原则"的体现：`executeSingle` 负责协调整个生命周期，但每个步骤的具体逻辑（权限、Hook、压缩、Metrics）都在各自模块里实现。

---

## 3.8 面试官可能问的问题

**Q：你的工具系统是怎么设计的？**

> 分两层：工具定义层和工具调度层。定义层每个工具实现 `Tool` 接口，声明名字、描述、JSON Schema（给 LLM 看）和并发分类（READ/WRITE/COMMAND）。调度层 `StreamingExecutor` 用"相邻性分批"算法调度：连续 READ 工具合并为并发批，遇到 WRITE/COMMAND 就切断开新批，批次间强等待。这样读密集场景大幅提速，同时保证任意读写序列的执行语义与全串行一致。

**Q：EditFileTool 为什么要求 old_string 唯一？**

> 核心是确定性。如果 old_string 在文件里出现多次，LLM 说的是"改第几个"？没有标准答案。与其猜测，不如报错让 LLM 提供更多上下文（更长的 old_string 直到唯一）。这样每次 edit 操作都是精确的、可预期的，不会有任何歧义。

**Q：BashTool 的 exit code 为什么不设置 isError？**

> 因为 exit code 非零不代表命令"失败"了。`grep` 没找到匹配返回 1，`diff` 发现文件有差异返回 1，这些都是正常行为。如果把 isError 设为 true，LLM 会错误地认为这些工具"出错了"，可能触发不必要的重试或错误的行为。我们的做法是把 exit code 附加到输出里，让 LLM 自己判断含义。

**Q：工具调度为什么用 Virtual Thread 而不是线程池？**

> 工具执行主要是 IO（读文件、执行命令），Virtual Thread 在 IO 阻塞时自动挂起释放 Carrier Thread，比线程池更高效。更重要的是，用 `newVirtualThreadPerTaskExecutor()` + `try-with-resources`，关闭时自动等待所有任务完成，实现批次间强等待，代码更简洁，语义更清晰。

**Q（追问）：你只讲了"READ 不能跨过 WRITE"，那两个 WRITE 之间为什么也不能并发？**

> 分两种情况。改**同一个文件**的两个 WRITE 并发跑，会有 last-write-wins 数据丢失——两个线程各自读原文、各自改、各自写回，后写的覆盖先写的，一次修改就丢了。改**不同文件**的两个 WRITE 理论上无冲突，但框架统一保守处理：WRITE/COMMAND 一律串行。原因是并发收益不值得冒险——WRITE 的耗时主要在磁盘写（很快），并发省不了多少时间，却要引入"判断两个 WRITE 是否触及同一资源"的复杂分析（COMMAND 更是可能有任意副作用，根本没法静态判断）。所以设计选择是：READ 并发（收益大、零风险），WRITE/COMMAND 串行（收益小、风险大）。这不是不能做得更细，是"收益不抵复杂度"的工程取舍。

**Q（追问）：一批并发 READ 里有一个失败了，其他的结果还要不要？**

> 要。并发批里每个工具独立执行、独立产出结果，一个 ReadFile 因为文件不存在而失败（返回 isError 的 ToolResult），不影响同批其他 READ 的结果。`future.get()` 收集所有结果，失败的那个作为一条带 isError 的 tool_result 回传给 LLM，LLM 看到"这个文件读失败了"会自己调整（换个路径、或先确认文件存在）。关键是**失败被降级成一条正常的错误结果**，而不是抛异常炸掉整批——这是第4章 isError 设计的价值：让 LLM 感知失败并自愈，而不是让框架崩溃。

---

## 3.9 生产中可能遇到的问题

**问题1：工具 schema 描述写得太简单，LLM 频繁调用错工具**

例子：有 ReadFile 和 Grep 两个工具，如果 description 没说清楚区别，LLM 可能用 ReadFile 读一个大文件再自己搜索，而不是直接用 Grep。

解法：description 里明确写"什么时候用我，什么时候用其他工具"。BashTool 的描述里就有：
> "Avoid using this tool to run cat, head, tail... Instead use the dedicated ReadFile, EditFile tools"

这就是主动引导 LLM 做出正确选择。

**问题2：EditFileTool 原子写在某些文件系统上失败**

`ATOMIC_MOVE` 在跨设备移动时（临时目录和目标目录在不同磁盘）会失败。
解法：临时文件创建在目标文件的同目录（`path.getParent()`），保证同一文件系统，原子移动就能成功。CodePal 代码里已经这样做了。

**问题3：BashTool 被模型用来执行高危命令**

没有权限控制的情况下，模型可能调 `rm -rf /` 或 `curl ... | bash`。
解法：
1. 权限系统（第6章）对 Bash 命令逐条检查，危险命令弹窗确认
2. OS 级沙箱（macOS seatbelt / Linux bwrap）在内核级禁止越权操作
3. BashTool 的 description 里明确写 Git Safety Protocol，引导模型不做破坏性操作

**问题4：并发批次里一个工具超时，拖住整批**

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = batch.calls().stream()
            .map(call -> executor.submit(() -> executeSingle(call)))
            .toList();
    for (var future : futures) results.add(future.get());  // ← 这里没有超时
}
```

当前实现对 `future.get()` 没有设置超时，如果某个工具阻塞了（比如 BashTool 里的命令没有正确超时），整批就卡住了。

优化方向：改为 `future.get(timeout, TimeUnit.SECONDS)`，超时后取消其他未完成的 future，返回超时错误，让整批继续推进。每个工具配独立超时参数会更灵活。

**问题5（用户视角）：并发批的工具日志在 TUI 里交错，用户看不懂谁是谁**

三个 ReadFile 并发跑，输出几乎同时到达，如果 TUI 直接按到达顺序打印，用户会看到"读取 A… 读取 B… A 完成… 读取 C… C 完成… B 完成…"这种交错，很难跟踪每个工具的状态。

解法：每个工具调用分配独立的显示区域/行（按 toolId 归组），并发执行时各自更新自己的那一行（"ReadFile A ⏳ → ✓"），而不是共用一条滚动输出。让并发的视觉呈现清晰可辨，是并发执行必须配套的 UI 设计。

**问题6（用户视角）：工具结果太长把终端刷屏，用户被淹没**

Agent 读了个几千行的文件，或跑了个输出巨大的命令，原始结果全打到终端，用户想看的信息被冲走。

解法：TUI 层对工具结果做**折叠显示**——默认只显示摘要（"读取 Agent.java（543 行）"），用户想看细节可展开。这既保护用户视野，也和第8章的"大结果落盘"呼应（发给 LLM 的和显示给用户的都不该是未经处理的原始洪流）。工具输出的呈现，是"给模型看"和"给用户看"两套逻辑，不能混为一谈。

---

*下一章：让 Agent 自己干活 — Agent Loop 主循环的完整实现*
