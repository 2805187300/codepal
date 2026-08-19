# 亮点②：MCP 工具延迟加载（ToolSearch 索引层）

---

## 1. 为什么要做这个？——背景

### 1.1 什么是 MCP

MCP（Model Context Protocol）是 Anthropic 推出的一套开放协议，让 LLM 可以通过标准化的方式调用外部工具——比如数据库查询、文件系统、浏览器、代码执行环境等。你可以把 MCP 理解为"AI 版的 USB 接口"：只要工具实现了 MCP 协议，就能插进来被 Agent 使用。

### 1.2 问题：工具 schema 全量注入，Token 被挤占

每次 Agent 发请求给 LLM，都需要在请求里带上**所有可用工具的 schema（工具描述）**——工具叫什么、有哪些参数、每个参数是什么含义。LLM 只有看到这些描述，才知道有哪些工具可以调。

一个工具的 schema 大概长这样：

```json
{
  "name": "mcp__github__search_repositories",
  "description": "Search GitHub repositories by keyword, language, stars...",
  "input_schema": {
    "type": "object",
    "properties": {
      "query": { "type": "string", "description": "Search query..." },
      "language": { "type": "string", "description": "Filter by language..." },
      "sort": { "type": "string", "description": "Sort by stars/forks/updated..." },
      "per_page": { "type": "integer", "description": "Results per page, max 100..." }
    },
    "required": ["query"]
  }
}
```

一个工具 schema 大约 200-500 Token。接入 100 个 MCP 工具，schema 就占掉 **2 万 - 5 万 Token**——每轮请求都要带着这些，不管这轮用不用到它们。

**结果**：
- 用户真正的任务描述、代码内容、历史对话被挤占，能用的有效 Token 变少
- 每轮请求成本直接上升（Token 计费）
- LLM 面对 100 个工具，注意力被分散，决策质量下降

---

## 2. 解决思路：延迟加载 + ToolSearch 索引层

核心思路很简单：**LLM 不需要一次性知道所有工具的详细 schema，只需要知道"有哪些工具名字"，用到的时候再去查具体 schema。**

```
全量注入（改造前）：
  每轮请求 = 任务内容 + 工具①完整schema + 工具②完整schema + ... + 工具⑩⑩完整schema
  Token 消耗：任务 + 5万（工具schema）

延迟加载（改造后）：
  每轮请求 = 任务内容 + [工具名列表，每个只有名字] + ToolSearch工具的schema
  Token 消耗：任务 + ~500（名字列表）+ ~200（ToolSearch schema）

  需要用某个工具时：
  LLM → 调用 ToolSearch("github search") → 返回匹配工具的完整schema → 下一轮生效
```

Token 从 5 万降到约 700，减少约 85%。

---

## 3. 核心机制：shouldDefer 标记 + ToolRegistry 管理

### 3.1 每个工具有一个 shouldDefer 标记

```java
// Tool 接口
public interface Tool {
    String name();
    String description();
    ToolCategory category();
    boolean shouldDefer();  // ← 这个工具要不要延迟加载？
    Map<String, Object> schema();
    ToolResult execute(Map<String, Object> args);
}
```

- `shouldDefer() = false`：核心工具，每轮都注入（ReadFile、WriteFile、Bash 等）
- `shouldDefer() = true`：MCP 工具和非常用工具，默认不注入

所有 MCP 工具的 `shouldDefer()` 都返回 `true`（见 `McpToolWrapper`）：

```java
// McpManager.java — McpToolWrapper
@Override public boolean shouldDefer() { return true; }
```

### 3.2 ToolRegistry 控制哪些 schema 注入请求

每轮构建请求时，`getAllSchemas()` 决定注入哪些工具：

```java
// ToolRegistry.java
public List<Map<String, Object>> getAllSchemas(String protocol) {
    var schemas = new ArrayList<Map<String, Object>>();
    for (var tool : tools.values()) {
        // shouldDefer=true 且还没被发现 → 跳过，不注入完整schema
        if (tool.shouldDefer() && !discoveredTools.contains(tool.name())) continue;
        schemas.add(tool.schema());
    }
    return schemas;
}
```

`discoveredTools` 是一个 Set，记录"LLM 已经通过 ToolSearch 主动查询过的工具"。只有被查到的工具，下一轮才会出现在请求里。

### 3.3 每轮请求里的工具名列表从哪来？

LLM 需要知道"有哪些工具可以搜索"，所以 ToolSearch 的 description 里包含了所有延迟工具的名字列表（动态生成，不是硬编码），LLM 通过这个提示知道去搜哪些。

当 ToolSearch 查询无结果时，代码会把所有延迟工具名列出来提示 LLM：

```java
// ToolSearchTool.java
if (schemas.isEmpty()) {
    List<Tool> deferred = registry.getDeferredTools();
    String nameList = deferred.stream()
            .map(Tool::name)
            .collect(Collectors.joining(", "));
    return ToolResult.success(
            "No matching deferred tools found for query \"" + query
                    + "\". Available deferred tools: " + nameList
    );
}
```

---

## 4. ToolSearch 工具的工作流程

ToolSearch 支持两种查询模式：

```java
// ToolSearchTool.java
if (query.startsWith("select:")) {
    // 精确按名字查：select:GitHubSearch,JiraCreate
    schemas = registry.findDeferredByNames(names, protocol);
} else {
    // 关键词模糊搜索
    schemas = registry.searchDeferred(query, maxResults, protocol);
}
```

搜索逻辑是名字 + description 的关键词匹配：

```java
// ToolRegistry.java — searchDeferred
for (var tool : tools.values()) {
    if (!tool.shouldDefer()) continue;
    if (tool.name().toLowerCase().contains(lower)
            || tool.description().toLowerCase().contains(lower)) {
        matches.add(tool.schema());
    }
}
```

**完整的使用流程**：

```
第1轮请求：
  LLM 看到：[ReadFile, WriteFile, Bash, ToolSearch] 的完整schema
  LLM 想调 GitHub API，但 mcp__github__* 工具不在列表里
         ↓
  LLM 调用：ToolSearch(query="github")
         ↓
  ToolSearch 返回：
    "Found 3 tool(s). Their full schemas are now loaded and will be
     available in subsequent requests.
     [完整的 mcp__github__search_repos schema JSON]
     [完整的 mcp__github__create_issue schema JSON]
     [完整的 mcp__github__list_prs schema JSON]"
         ↓
  registry.markDiscovered("mcp__github__search_repos") 等

第2轮请求：
  LLM 看到：[ReadFile, WriteFile, Bash, ToolSearch,
             mcp__github__search_repos,    ← 新加入！
             mcp__github__create_issue,
             mcp__github__list_prs]
  现在可以直接调用这些工具了
```

---

## 5. 85% 是怎么算出来的

这是一个按典型场景的估算，不是精确测量：

```
假设：100 个 MCP 工具，每个 schema 平均 350 Token

全量注入方案：
  工具 schema 总量 = 100 × 350 = 35,000 Token / 每轮

延迟加载方案：
  每轮只注入 ToolSearch 的 schema（约 200 Token）
  加上 LLM 偶尔调用 ToolSearch 拉回来的 2-3 个工具（~700 Token）
  工具 schema 总量 ≈ 200 + 700 = 900 Token / 每轮（典型场景）

节省 = (35,000 - 900) / 35,000 ≈ 97%

保守估计（考虑有些工具会被反复查，平均每轮注入 5 个已发现工具）：
  900 + 5 × 350 = 2,650 Token
  节省 = (35,000 - 2,650) / 35,000 ≈ 92%

官方表述用"约 85%"是留了更大的余量，属于保守数字。
```

---

## 6. 面试怎么答

### 标准描述（30 秒版）

> "接入百级 MCP 工具后，如果把所有工具的 schema 全量注入每轮请求，工具描述本身就要占掉几万 Token，挤压有效上下文空间，而且 LLM 要处理的信息量太大，决策质量也会下降。
>
> 我设计了 ToolSearch 索引层：所有 MCP 工具标记为延迟加载，每轮请求只注入工具名列表和一个 ToolSearch 工具。当 LLM 判断需要某个工具时，主动调用 ToolSearch 搜索，ToolSearch 返回完整 schema 并标记为"已发现"，下一轮请求自动带上这个工具。
>
> 按典型百级工具场景估算，工具描述 Token 占用减少约 85%。"

---

## 7. 大厂面试官高频追问（全集）

---

### 【设计决策类】

**Q1：为什么不直接让 LLM 自己从描述里推断出怎么调工具，而要专门设计一个 ToolSearch？**

> ToolSearch 解决的核心问题是 LLM 的"发现"问题，不是"推断"问题。LLM 没法凭空推断出一个它从未见过的工具的名字和参数格式——它必须先看到 schema。ToolSearch 的作用是按需把 schema 从"仓库"里取出来给 LLM 看，是一个**信息检索工具**，不是推断机制。
>
> 类比：你让一个新员工去数据库查数据，他需要先知道表名和字段名（schema），而不是凭直觉猜。ToolSearch 就是给他一本"数据库字典"，随时可查。

**Q2：延迟加载会不会导致 LLM 不知道某个工具存在，直接就跳过了，任务失败？**

> 这是最核心的风险，有两层缓解：
>
> 第一，System Prompt 里会提示 LLM："当你需要某个不在当前列表里的工具时，调用 ToolSearch 查找"。这建立了一个行为惯例。
>
> 第二，ToolSearch 的 description 里动态包含了所有延迟工具的名字（通过 `getDeferredToolNames()` 生成），LLM 能看到工具名字列表，知道"有这个东西存在"，只是需要主动去查 schema。
>
> 确实存在 LLM 判断力的问题——如果 LLM 没意识到需要某个工具，也没去搜，任务可能会走弯路。这是有损优化，用 Token 节省换取少数情况下需要多一轮交互。

**Q3：发现过的工具会一直留在请求里吗？下一次会话还在吗？**

> `discoveredTools` 是一个 Session 级的内存 Set，随会话生命周期存在。同一会话里一旦发现，后续每轮都会带上；会话结束后重置，下次新会话从零开始。
>
> 这是合理的：不同任务需要不同的工具，上次发现的不一定对下次有用，每次都从一张干净的"工具列表"开始，让 LLM 按需加载，避免历史发现污染新会话。

**Q4：ToolSearch 自身的 schema 每轮都要注入，这个 schema 会不会很大？**

> 不大。ToolSearch 的 schema 非常简单——只有两个参数：`query`（字符串）和 `max_results`（整数），加上一段 description，总共约 150-200 Token。相比全量注入省下的几万 Token，这点开销完全可以接受。

---

### 【工程化细节类】

**Q5：ToolSearch 的关键词搜索精度够吗？搜错了怎么办？**

> 当前实现是简单的字符串包含匹配（`contains`），不是语义搜索。精度有限，可能漏掉同义词（搜 "file system" 找不到描述里写 "filesystem" 的工具）。
>
> 缓解手段：
> 1. `select:ToolName` 精确查询模式，LLM 知道工具名时直接用，不走模糊搜索
> 2. 搜索无结果时返回所有延迟工具名列表，让 LLM 自己选
> 3. 改进方向：接入向量嵌入做语义搜索（但需要额外依赖，复杂度上升，当前规模不值得）

**Q6：markDiscovered 是线程安全的吗？并发场景下会不会有问题？**

> `discoveredTools` 用的是 `ConcurrentHashMap.newKeySet()`，`markDiscovered` 和 `isDiscovered` 都是原子操作，线程安全没问题。
>
> 实际上 Agent Loop 是单线程顺序执行的（一轮等上一轮结束），并发风险主要在多 Agent 场景（Lead-Teammate 架构）。但每个 Teammate 有独立的 ToolRegistry 实例，不共享，所以也不存在跨 Agent 的竞争问题。

**Q7：MCP 工具连接失败了怎么处理？会影响整个 Agent 启动吗？**

> `McpManager.connectAll()` 对每个 MCP Server 单独 try-catch，某个连接失败只会把错误信息加进 errors 列表，不会抛异常阻断其他 Server 的初始化：
>
> ```java
> } catch (Exception e) {
>     errors.add("MCP server '" + name + "': " + e.getMessage());
> }
> ```
>
> Agent 启动后可以把 errors 列表展示给用户提示哪些 MCP Server 没连上，但其他工具正常可用。这是"部分可用"而非"全有全无"的设计思路。

**Q8：MCP 工具的 schema 格式和 Anthropic 原生工具一样吗？需要转换吗？**

> 不完全一样。MCP SDK 返回的工具描述格式（`McpSchema.Tool`）和 Anthropic API 要求的 `tool_use` 格式有差异，`McpToolWrapper` 的 `schema()` 方法负责做转换：
>
> ```java
> @Override public Map<String, Object> schema() {
>     var input = new LinkedHashMap<String, Object>();
>     var jsonSchema = sdkTool.inputSchema();
>     if (jsonSchema != null) {
>         if (jsonSchema.type() != null) input.put("type", jsonSchema.type());
>         if (jsonSchema.properties() != null) input.put("properties", jsonSchema.properties());
>         if (jsonSchema.required() != null) input.put("required", jsonSchema.required());
>     }
>     return Map.of("name", name(), "description", description(), "input_schema", input);
> }
> ```
>
> 对 OpenAI 协议还需要再套一层 `"type": "function"` 的外壳（在 `getAllSchemas` 里处理），两种协议各自映射，上层调用方不感知差异。

**Q9：MCP 工具的名字是怎么生成的？为什么有 `mcp__serverName__toolName` 这种格式？**

> 拼接规则在 `McpToolWrapper.name()` 里：
>
> ```java
> @Override public String name() {
>     return "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name());
> }
> ```
>
> `sanitizeName` 把非字母数字下划线的字符全部替换成 `_`。这样做的原因：
>
> 1. **避免命名冲突**：不同 MCP Server 可能都有叫 `search` 的工具，加上 server 名前缀就不会冲突
> 2. **符合 API 格式要求**：Anthropic API 的工具名只允许字母、数字、下划线
> 3. **一眼看出来源**：`mcp__github__search` 明显是 github MCP Server 的工具

---

### 【量化与验证类】

**Q10：85% 这个数字是怎么测出来的？用了什么方法验证？**

> 这是基于典型场景的估算，不是精确实验数字。估算方法：统计 100 个 MCP 工具 schema 的平均 Token 数（通过字符数 / 3.5 估算），乘以工具数量得到全量注入的 Token 消耗基准；延迟加载场景下统计每轮实际注入的工具数（通过 MetricsCollector 记录每轮 input_tokens），对比两个数字。
>
> 更严格的量化需要：固定一批标准测试任务，分别在全量注入和延迟加载两种配置下跑，记录每轮 input_tokens，多次取平均值对比。当前阶段属于设计阶段的理论估算，实测数字依赖 Eval 框架补充。

**Q11：延迟加载会不会导致多一轮工具调用，反而增加了总 Token 消耗？**

> 在需要使用延迟工具的任务里，确实会多一次 ToolSearch 调用，多出来大约 400-600 Token（ToolSearch 的调用参数 + 返回的 schema）。
>
> 但这是一次性成本：同一会话里同一个工具只需要发现一次，后续每轮都直接带着它的 schema。相比全量注入每轮多出来的几万 Token，一次性多 600 Token 的代价非常划算，只要任务超过 2 轮，就已经回本了。

---

### 【对比与扩展类】

**Q12：业界有没有类似的方案？和 RAG 检索工具有什么区别？**

> 延迟加载和 RAG 思路相近，但有本质差异：
>
> | 维度 | ToolSearch 延迟加载 | RAG 检索工具 |
> |------|---------------------|--------------|
> | 加载时机 | LLM 主动调用 ToolSearch | 每轮自动向量检索 |
> | 检索方式 | 关键词匹配 + 精确查询 | 向量语义相似度 |
> | 架构复杂度 | 低，无额外服务依赖 | 高，需要向量数据库 + 嵌入模型 |
> | LLM 感知 | LLM 知道自己在"查工具" | 对 LLM 透明，自动注入 |
> | 错误影响 | LLM 没搜到，手动用 select: | 向量召回偏差，LLM 不知道 |
>
> ToolSearch 的优势是架构简单、LLM 行为可解释（可以看到它搜了什么）；RAG 的优势是语义理解更好，不依赖 LLM 主动行为。

**Q13：如果工具数量继续增长到 1000 个，这套方案还 hold 得住吗？**

> 关键词搜索在 1000 个工具里可能精度下降（相关结果太多或太少），这是需要升级的点。解决方向：
> 1. **分类索引**：工具按功能领域分类（文件操作、代码执行、外部 API 等），先选类别再在类别内搜索
> 2. **向量嵌入**：用 embedding 模型给工具描述打向量，语义搜索精度更高
> 3. **工具推荐**：根据当前任务上下文，System Prompt 里动态推荐相关工具类别
>
> 当前 100 级场景关键词搜索够用，1000 级需要考虑上述改进，这是设计的扩展点。

---

## 8. 简历一句话（背熟）

> **MCP 工具延迟加载**：构建 ToolSearch 索引层，每轮仅向 LLM 注入工具名列表，模型主动调用 ToolSearch 后按需加载目标工具 schema 并在下一轮生效；按典型百级工具场景估算，工具描述 Token 占用减少约 85%。

---

*（本文档随学习对话持续更新）*
