# 第7章：MCP 协议 — 开放式工具生态

> 目标：理解 MCP 是什么协议、解决什么问题，CodePal 如何接入 MCP Server，以及"工具延迟加载"这个设计的来龙去脉。
> 对应简历亮点：第2条 — MCP 工具延迟加载，百级工具场景下 Token 占用减少 85%
> 关键文件：`mcp/McpManager.java`、`tool/impl/ToolSearchTool.java`、`config/McpServerConfig.java`

---

## 7.1 先理解 MCP 是什么

在没有 MCP 之前，Agent 能用的工具都是"内置工具"——开发者在代码里写死的，比如 ReadFile、EditFile、Bash。你想让 Agent 查一下 Jira ticket？抱歉，没这个工具，要自己写一个，还要改 Agent 的代码。

**MCP（Model Context Protocol）** 是 Anthropic 在 2024 年提出的开放协议，目标是：**让任意第三方可以开发"工具服务器"（MCP Server），Agent 通过标准协议连接，动态获取新工具能力，不需要修改 Agent 代码。**

打个比方：原来的工具是"出厂自带"的，MCP 让工具变成了"可插拔的 USB 设备"——只要符合 USB 规范（MCP 协议），任何外设都能接上来用。

**MCP 生态的实际情况（2025年）**：GitHub 上已经有上千个开源 MCP Server，覆盖了：
- `mcp-server-github`：操作 GitHub 仓库、PR、Issue
- `mcp-server-postgres`：查询 PostgreSQL 数据库
- `mcp-server-slack`：发送 Slack 消息
- `mcp-server-web-search`：联网搜索
- `mcp-server-jira`：查询 Jira 工单
- …… 以及无数私有内部工具

---

## 7.2 MCP 协议的通信方式

MCP 定义了两种传输方式，CodePal 都支持：

**方式一：Stdio（标准输入输出）**

Agent 把 MCP Server 作为子进程启动，通过 stdin/stdout 传递 JSON 消息：

```yaml
# .codepal/config.yaml
mcp_servers:
  - name: filesystem
    command: npx           # 启动命令
    args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    env:
      NODE_ENV: production
```

工作流程：
```
Agent 进程
  └── 启动子进程：npx -y @modelcontextprotocol/server-filesystem /tmp
        ↕ stdin/stdout（JSON-RPC 协议）
        MCP Server 进程
```

这种方式适合本地工具，启动即用，不需要网络。

**顺带说清 JSON-RPC 是什么**（图里出现了这个词，很多人不熟）：JSON-RPC 是一个轻量的远程调用协议，用 JSON 表达三种消息——请求（`{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}`）、响应（带同一个 `id` 和 `result`）、通知（无 `id`、不需要回复）。MCP 底层就是用 JSON-RPC 来传"列出工具""调用工具"这些消息的。用 `id` 把请求和响应配对，所以即使消息乱序到达也能对上。理解这一层能回答"MCP 到底怎么通信的"——不是什么神秘协议，就是标准 JSON-RPC 跑在 stdio 或 HTTP 上。

**方式二：HTTP（可流式传输）**

连接远程 MCP Server，通过 HTTP 传输：

```yaml
mcp_servers:
  - name: my-api-tools
    url: https://my-internal-api.com/mcp
    headers:
      Authorization: "Bearer ${MY_API_TOKEN}"  # 支持环境变量替换
```

`${MY_API_TOKEN}` 这种写法由 `resolveEnvVars` 方法处理，在运行时替换为真实的环境变量值，API Key 不写死在配置文件里（安全实践）。

---

## 7.3 McpManager：连接和封装的核心

`McpManager` 负责建立连接、获取工具列表、把 MCP 工具包装成 CodePal 的 `Tool` 接口：

```java
// mcp/McpManager.java

public ConnectResult connectAll() {
    var tools = new ArrayList<Tool>();
    var errors = new ArrayList<String>();

    for (var entry : configs.entrySet()) {
        String name = entry.getKey();
        var cfg = entry.getValue();

        try {
            // 1. 创建客户端（Stdio 或 HTTP）
            var client = createClient(cfg);

            // 2. MCP 握手：初始化连接，协商协议版本
            client.initialize();
            clients.put(name, client);

            // 3. 列出 Server 提供的所有工具
            var result = client.listTools();

            // 4. 把每个 MCP 工具包装成 CodePal 的 Tool 接口
            for (var sdkTool : result.tools()) {
                tools.add(new McpToolWrapper(name, sdkTool, client));
            }
        } catch (Exception e) {
            // 单个 Server 连接失败不影响其他 Server
            errors.add("MCP server '" + name + "': " + e.getMessage());
        }
    }
    return new ConnectResult(tools, servers, errors);
}
```

**单个 Server 失败不崩溃**：注意 `try-catch` 把每个 Server 的连接独立包住，一个 MCP Server 挂了（比如 npx 命令找不到），其他 Server 和内置工具照常工作。这是生产级的容错设计。

---

## 7.4 McpToolWrapper：适配器模式的完美示范

MCP Server 的工具用 `McpSchema.Tool` 对象表示，但 CodePal 内部用 `Tool` 接口。`McpToolWrapper` 是一个适配器，把 MCP 格式适配成 CodePal 格式：

```java
private static class McpToolWrapper implements Tool {
    private final String serverName;
    private final McpSchema.Tool sdkTool;   // MCP SDK 的工具对象
    private final McpSyncClient client;      // 用于实际调用

    // 工具名：加上 server 前缀，防止不同 Server 的工具名冲突
    // 格式：mcp__serverName__toolName
    // 例：mcp__github__create_issue
    @Override
    public String name() {
        return "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name());
    }

    // description 直接用 MCP Server 提供的描述
    @Override
    public String description() {
        return sdkTool.description() != null ? sdkTool.description() : "";
    }

    // MCP 工具可能有副作用，统一归类为 COMMAND（不参与并发）
    @Override
    public ToolCategory category() { return ToolCategory.COMMAND; }

    // ← 关键：MCP 工具默认延迟加载
    @Override
    public boolean shouldDefer() { return true; }

    // schema 从 MCP 的 inputSchema 转换
    @Override
    public Map<String, Object> schema() {
        var input = new LinkedHashMap<String, Object>();
        var jsonSchema = sdkTool.inputSchema();
        if (jsonSchema != null) {
            if (jsonSchema.type() != null) input.put("type", jsonSchema.type());
            if (jsonSchema.properties() != null) input.put("properties", jsonSchema.properties());
            if (jsonSchema.required() != null) input.put("required", jsonSchema.required());
        }
        return Map.of("name", name(), "description", description(), "input_schema", input);
    }

    // 执行：通过 MCP 客户端远程调用
    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            var request = new McpSchema.CallToolRequest(sdkTool.name(), args);
            var result = client.callTool(request);
            String text = extractTextContent(result);
            boolean isError = result.isError() != null && result.isError();
            return isError ? ToolResult.error(text) : ToolResult.success(text);
        } catch (Exception e) {
            return ToolResult.error("MCP tool call failed: " + e.getMessage());
        }
    }
}
```

**`sanitizeName` 的作用**：

```java
private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");

static String sanitizeName(String name) {
    return NON_ALNUM.matcher(name).replaceAll("_");
}
```

MCP Server 的名字和工具名可能包含 `-`、`.`、空格等特殊字符（比如 `my-github-server`、`create-issue`），但 LLM 的工具名有格式要求。`sanitizeName` 把非字母数字下划线的字符全替换成 `_`，结果如 `mcp__my_github_server__create_issue`。

---

## 7.5 核心设计：工具延迟加载（Deferred Loading）

这是简历亮点第2条的核心。先理解问题，再看解法。

### 问题：百级工具 schema 挤爆上下文

假设你接入了 10 个 MCP Server，每个 Server 有 10 个工具，总共 100 个工具。每个工具的 schema（名字 + 描述 + 参数定义）平均占 200 token，100 个工具就是 **20000 token**。

这 20000 token 全部存在于 system prompt 或每次请求的 tools 字段里，**每轮请求都要发送**。对于 200K token 的上下文窗口，光工具 schema 就占了 10%，而绝大多数工具在当次任务中根本用不到。

这不仅浪费 token（多花钱），还挤占了真正有用的信息（对话历史、文件内容）的空间。

### 解法：两阶段工具暴露

**阶段一：只告诉 LLM"这些工具存在"，不给完整 schema**

在 `agentLoop` 每轮开始时：

```java
// Agent.java
var deferredNames = registry.getDeferredToolNames();
if (!deferredNames.isEmpty()) {
    var sb = new StringBuilder();
    sb.append("The following deferred tools are available via ToolSearch. ");
    sb.append("Their schemas are NOT loaded - use ToolSearch with ");
    sb.append("query \"select:<name>[,<name>...]\" to load tool schemas before calling them:\n");
    for (var dn : deferredNames) {
        sb.append(dn).append("\n");
    }
    conv.addSystemReminder(sb.toString());
}
```

LLM 看到的是一个简短的名字列表：
```
mcp__github__create_issue
mcp__github__list_prs
mcp__slack__send_message
...
```

每个名字大约 5-10 token，100 个工具也只有 500-1000 token，比完整 schema 节省 **85%** 以上。

**阶段二：LLM 需要某个工具时，调 ToolSearch 加载具体 schema**

```java
// ToolRegistry.java

// 延迟工具：注册了但默认不在 getAllSchemas() 的结果里
public List<Map<String, Object>> getAllSchemas(String protocol) {
    for (var tool : tools.values()) {
        // 如果是延迟工具且还没被 discover，跳过
        if (tool.shouldDefer() && !discoveredTools.contains(tool.name())) continue;
        schemas.add(convertSchema(tool.schema(), protocol));
    }
    return schemas;
}

// 搜索延迟工具（关键字匹配名字或描述）
public List<Map<String, Object>> searchDeferred(String query, int maxResults, String protocol) {
    String lower = query.toLowerCase();
    for (var tool : tools.values()) {
        if (!tool.shouldDefer()) continue;
        if (tool.name().toLowerCase().contains(lower)
                || tool.description().toLowerCase().contains(lower)) {
            matches.add(convertSchema(tool.schema(), protocol));
        }
    }
    return matches;
}

// 标记为"已发现"，下次 getAllSchemas 就会包含它
public void markDiscovered(String name) {
    discoveredTools.add(name);
}
```

---

## 7.6 ToolSearchTool：按需加载的触发器

`ToolSearchTool` 本身是一个内置工具（不延迟加载，`shouldDefer() = false`），专门用来加载其他延迟工具的 schema：

```java
@Override
public ToolResult execute(Map<String, Object> args) {
    String query = stringArg(args, "query", "");
    int maxResults = intArg(args, "max_results", 5);  // 最多返回 20 个

    List<Map<String, Object>> schemas;

    if (query.startsWith("select:")) {
        // 精确选择：select:ToolA,ToolB
        List<String> names = Arrays.stream(query.substring("select:".length()).split(","))
                .map(String::trim)
                .toList();
        schemas = registry.findDeferredByNames(names, protocol);
    } else {
        // 关键字搜索
        schemas = registry.searchDeferred(query, maxResults, protocol);
    }

    if (schemas.isEmpty()) {
        // 没找到：列出所有延迟工具的名字，帮助 LLM 纠正查询
        List<Tool> deferred = registry.getDeferredTools();
        String nameList = deferred.stream().map(Tool::name).collect(Collectors.joining(", "));
        return ToolResult.success("No matching tools found. Available: " + nameList);
    }

    // ← 关键：标记为已发现，下一次请求就能直接调用
    for (var s : schemas) {
        if (s.get("name") instanceof String n) {
            registry.markDiscovered(n);
        }
    }

    // 把完整 schema 以 JSON 形式返回给 LLM
    String schemasJson = MAPPER.writeValueAsString(schemas);
    return ToolResult.success(
        "Found " + schemas.size() + " tool(s). Their full schemas are now loaded "
        + "and will be available in subsequent requests.\n\n" + schemasJson
    );
}
```

**完整的两轮交互过程：**

```
第 1 轮：
  LLM 看到 system reminder：
    "deferred tools available: mcp__github__create_issue, mcp__slack__send_message, ..."
  LLM 想创建 GitHub issue，调：
    ToolSearch(query="select:mcp__github__create_issue")

  ToolSearch 执行：
    - 在 registry 里找到 mcp__github__create_issue 的 schema
    - 调用 registry.markDiscovered("mcp__github__create_issue")
    - 返回完整 schema JSON 给 LLM

第 2 轮：
  agentLoop 调 registry.getAllSchemas()
    → discoveredTools 里有了 mcp__github__create_issue
    → 这次的 tools 列表里包含了完整 schema

  LLM 现在有完整 schema，可以直接调用：
    mcp__github__create_issue(title="...", body="...", labels=["bug"])
```

**为什么需要两轮？**

因为 LLM 在调 ToolSearch 的那一轮里，它的 `tools` 列表还没有目标工具的 schema（只有名字列表）。ToolSearch 把 schema 返回给 LLM 是在工具结果里，LLM 能"看到"这个 schema 并理解，但**只有在下一轮 API 调用时**，这个 schema 才会出现在 `tools` 字段里，LLM 才能真正"调用"它。

这是协议层面的约束：工具调用能力来自于 API 请求里的 `tools` 字段，不来自于对话历史里的内容。

---

## 7.7 从 token 节省的角度量化这个设计

用实际数字感受一下：

| 场景 | 无延迟加载 | 有延迟加载 |
|------|-----------|-----------|
| 100 个工具，全量注入 | 100 × 200 = 20000 token/轮 | 100 × 8 = 800 token/轮（名字列表）|
| 使用其中 3 个工具 | 20000 token | 800 + 3 × 200 = 1400 token |
| 节省比例 | — | (20000-1400)/20000 = **93%** |

当然，多了一轮 ToolSearch 调用（几十 token 的开销），但总体节省仍然非常显著。

**额外收益**：LLM 的注意力是有限的，工具列表越短，LLM 越能专注于真正相关的工具，减少"选错工具"的概率。

---

## 7.8 Windows 兼容性处理

```java
static String windowsSafe(String command) {
    if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return command;
    String base = command.toLowerCase();
    if (WIN_CMD_SUFFIXED.contains(base)) return command + ".cmd";
    return command;
}

private static final Set<String> WIN_CMD_SUFFIXED = Set.of(
        "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");
```

在 Windows 上，`npx`、`npm` 等命令实际上是 `npx.cmd`、`npm.cmd`。如果直接用 `npx` 作为命令，`ProcessBuilder` 找不到可执行文件会报错。这个方法在 Windows 上自动加上 `.cmd` 后缀。

这是一个细节但重要的跨平台兼容性处理，面试时提到能展示工程经验。

---

## 7.9 MCP vs Function Calling vs Skill：三者关系

这是面试高频考点，一定要说清楚：

```
Function Calling（底层协议）
  └── 所有工具调用走这条路
      LLM → 输出 tool_use → Agent 执行 → 结果回传

MCP（工具集扩展）
  └── 解决"Agent 能做什么"
      把外部服务的能力包装成工具，动态扩展工具箱
      MCP 工具通过 Function Calling 被 LLM 调用

Skill（行为模式扩展）
  └── 解决"Agent 该怎么做"
      本质是 prompt 配置 + 工具过滤
      不提供新工具能力，而是约束和引导 Agent 的思路
```

类比：
- Function Calling 是"嘴和手的连接方式"（通信协议）
- MCP 是"往工具箱里加工具"（能力扩展）
- Skill 是"给 Agent 一份任务手册"（行为约束）

---

## 7.10 面试官可能问的问题

**Q：什么是 MCP 协议？你们是怎么用的？**

> MCP 是 Anthropic 提出的开放工具协议，让第三方可以开发工具服务器，Agent 通过标准协议动态接入新工具能力，不需要修改 Agent 代码。我们支持两种传输方式：Stdio（把 MCP Server 作为子进程，通过 stdin/stdout 通信）和 HTTP（连接远程 MCP Server）。所有 MCP 工具被包装成统一的 `Tool` 接口，对 Agent 的调度层完全透明。

**Q：你说 MCP 工具延迟加载节省了 85% 的 token，怎么计算的？**

> 一个接了 10 个 MCP Server、共 100 个工具的场景下，每个工具 schema 平均 200 token，全量注入是 20000 token/轮。延迟加载后，每轮只发工具名字列表（约 800 token），需要某个工具时才调 ToolSearch 加载完整 schema（+200 token/个，一般用 3-5 个）。总 token 约 1400/轮，节省约 93%。就算保守估计，85% 是有把握的。

**Q：ToolSearch 的两种查询模式有什么区别？**

> 两种：`select:ToolA,ToolB` 是精确按名字选取，适合 LLM 已经从名字列表里看到了目标工具、想直接加载；关键字搜索适合 LLM 只知道自己想做什么（比如"search"、"github"），系统帮它找匹配的工具。后者用名字和描述做匹配，命中后同样标记为 discovered，下一轮就能调用。

**Q（追问）：延迟加载省了 token，但代价是什么？85% 的场景真的划算吗？**

> 有两个代价，要诚实说：
> - **多一轮往返延迟**：LLM 得先调一次 ToolSearch 才能拿到 schema，多了一轮 LLM 调用（几秒延迟 + 一点 token）。
> - **模型可能"不知道去搜"**：如果 system reminder 里的工具名列表 LLM 没看懂，它可能压根没意识到该用 ToolSearch，导致该用的工具没用上。
>
> 划不划算取决于**工具数量和使用密度**。工具多（几十上百个）、单次任务只用几个 → 延迟加载大赚（省 85%+，多一轮往返可忽略）。工具少（比如只有 10 个）→ 全量注入才几千 token，延迟加载省的还不够那一轮 ToolSearch 的开销，反而是负优化。所以 CodePal 只对 `shouldDefer()=true` 的工具（主要是 MCP 的海量工具）延迟加载，内置的十几个高频工具直接全量注入。**方案要匹配工具规模**——又是这个原则。

**Q（追问）：MCP 除了 Tools，还能提供什么？**

> MCP 有四类原语，Tools 只是其一：
> - **Tools（工具）**：可执行的动作（本章讲的，最常用）。
> - **Resources（资源）**：可读的数据源（文件、数据库记录、API 响应），Agent 可以像读文件一样读取。
> - **Prompts（提示模板）**：Server 预定义的 prompt 模板，供用户/Agent 复用。
> - **Sampling（采样）**：Server 反向请求 Agent 帮它调 LLM（较少用）。
>
> CodePal 目前主要用 Tools，但知道 MCP 是个"能提供工具、数据、模板"的完整上下文协议，而不只是"工具协议"，被追问时不至于露怯。

**Q（追问）：MCP 和普通 HTTP API 调用的本质区别是什么？MCP 到底新在哪？**

> 很多人答不清这个。普通 HTTP API 是"点对点"的——你要用某个 API，得读它的文档、按它的格式写调用代码、硬编码进你的程序。**MCP 的新在于"自描述 + 标准化发现"**：MCP Server 会主动告诉客户端"我有哪些工具、每个工具的参数 schema 是什么"（`tools/list`），客户端不用预先知道，运行时动态发现即可。所以同一个 Agent 不改一行代码，就能接入任意符合 MCP 的 Server——这才是"USB 化"的含义。普通 API 是"每个都要专门适配"，MCP 是"一次适配，即插即用"。区别不在传输（都可以是 HTTP），在于**接口的标准化和自描述能力**。

---

## 7.11 生产中可能遇到的问题

**问题1：MCP Server 启动慢，Agent 启动时卡住**

`connectAll()` 是同步的，所有 Server 串行连接。如果某个 Server 启动慢（比如 npx 需要下载包），整个 Agent 启动都会等待。

优化方向：并行连接所有 Server：
```java
// 可改成 CompletableFuture 并发初始化
var futures = configs.entrySet().stream()
    .map(entry -> CompletableFuture.supplyAsync(() -> connectOne(entry)))
    .toList();
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

**问题2：MCP Server 运行中崩溃，工具调用报错**

`McpToolWrapper.execute` 里已经有 try-catch，连接断开会返回 `ToolResult.error("MCP tool call failed: ...")`。LLM 收到错误信息后会知道这个工具不可用，通常会换其他方案。

但没有重连机制：Server 崩溃后，后续所有调用这个 Server 的工具都会报错，需要重启 Agent 才能恢复。生产系统应该加重连逻辑。

**问题3：不同 MCP Server 的工具名冲突**

比如两个 Server 都有叫 `search` 的工具，`sanitizeName` 之后变成 `mcp__server1__search` 和 `mcp__server2__search`，通过 Server 名字前缀区分，不会冲突。这是名字空间设计。

**问题4：ToolSearch 的关键字搜索不准**

关键字搜索是简单的 `contains` 匹配，没有语义理解。如果工具描述写得很简洁，可能搜不到。

解法：鼓励 MCP Server 给工具写详细描述，包含使用场景和关键词。也可以引入向量搜索（把工具描述向量化，按语义相似度排名），但 CodePal 目前用简单字符串匹配，够用且不引入额外依赖。

**问题5（用户视角）：用户配了 MCP Server 但连不上，一头雾水**

用户在 config.yaml 里配了个 MCP Server，启动后发现工具没出现。可能是命令名写错、npx 没装、网络不通、认证 token 失效……但如果框架只是静默地"少了几个工具"，用户根本不知道哪出了问题。

解法：`connectAll()` 收集的 `errors` 列表要**显式呈现给用户**——启动时打印"MCP server 'github' 连接失败：command not found: npx"这样的明确信息，而不是默默跳过。`/mcp` 命令应能查看每个 Server 的连接状态和失败原因。连接是最容易出问题的环节，错误信息的清晰度直接决定用户能不能自己排查。

**问题6（用户视角）：工具名带 `mcp__github__` 前缀，用户看着困惑**

Agent 在 TUI 里显示"正在调用 mcp__github__create_issue"，普通用户看到这串带下划线前缀的名字会觉得莫名其妙、不知道是什么。

解法：面向用户显示时做**友好化呈现**——把 `mcp__github__create_issue` 显示成"GitHub · 创建 Issue"，前缀只是内部命名空间，不该直接甩给用户看。内部标识和用户呈现要分开。

**问题7（用户视角）：不可信的第三方 MCP Server 带来供应链风险**

用户从网上找了个 MCP Server 接进来，这个 Server 可能在工具描述里藏注入指令（"工具投毒"），或者它执行的动作本身就是恶意的（偷偷上传你的代码）。用户往往意识不到"接一个 MCP Server"等于"信任一个第三方能操作我的环境"。

解法：一是权限系统兜底——MCP 工具统一归类为 COMMAND，危险操作照样过权限检查（第6章），不因为"是工具调用"就免检；二是首次接入陌生 Server 时提示用户确认信任；三是文档提醒用户只接入可信来源的 MCP Server。MCP 的开放性是双刃剑，便利和风险并存，用户需要被明确告知这个信任边界。

---

*下一章：上下文管理、可观测性与评测驱动优化*
