# 第2章：让 AI 开口说话

> 目标：理解 CodePal 如何与 LLM API 通信，流式调用的原理，以及 Prompt Cache 的工程实现。
> 关键文件：`llm/LlmClient.java`、`llm/AnthropicClient.java`、`llm/StreamEvent.java`

---

## 2.1 从"发请求"到"收回复"：全流程概览

想象你在终端里输入了一句话："帮我读一下 Agent.java"。这句话是怎么传到 Claude 那里，Claude 的回复又是怎么出现在你屏幕上的？

```
用户输入
   │
   ▼
ConversationManager（把历史打包成 messages 列表）
   │
   ▼
LlmClient.stream(conv, tools)  ← 发起 HTTP 请求
   │
   ▼ （HTTP SSE 流式返回）
BlockingQueue<StreamEvent>     ← 每来一个字就 put 进队列
   │
   ▼
Agent.agentLoop 里的 while(true)  ← 从队列取事件
   │
   ▼
AgentEvent → BlockingQueue<AgentEvent> ← 推给 TUI
   │
   ▼
终端实时显示文字（打字机效果）
```

两个队列串联：`StreamEvent` 队列负责 HTTP 层到 Agent 层，`AgentEvent` 队列负责 Agent 层到 UI 层。

---

## 2.2 LlmClient 接口：面向接口编程的教科书

```java
// llm/LlmClient.java
public interface LlmClient {

    BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools);

    default void setMaxOutputTokens(int tokens) {}

    void setSystemPrompt(String prompt);

    static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return switch (cfg.getProtocol()) {
            case "anthropic"     -> new AnthropicClient(cfg, systemPrompt);
            case "openai"        -> new OpenAiClient(cfg, systemPrompt);
            case "openai-compat" -> new OpenAiCompatClient(cfg, systemPrompt);
            default -> throw new IllegalArgumentException("Unknown protocol: " + cfg.getProtocol());
        };
    }
}
```

这里有两个设计值得注意：

**① 面向接口，而非面向实现**

`Agent.java` 里持有的是 `LlmClient` 接口，不是 `AnthropicClient`。这意味着：
- 切换模型提供商（从 Anthropic 换到 OpenAI）只需改配置，Agent 代码零改动
- 测试时可以注入一个 Mock LlmClient，不需要真正调 API

**② 工厂方法（`LlmClient.create`）**

用 `switch` 表达式根据协议名返回对应实现。Java 14+ 的 switch 表达式比传统 if-else 更简洁，且编译器能检查穷举性。

---

## 2.3 流式调用：为什么不等全文生成完再返回

**非流式（Batch）**：
```
你 → 发请求 → 等待 10 秒 → LLM 把全部回复生成完 → 一次性返回
```

问题：用户盯着空白屏幕等 10 秒，体验极差。对于 Agent 来说，工具调用的 JSON 也要等全部生成完才能开始执行，严重拖慢速度。

**流式（Streaming）**：
```
你 → 发请求 → LLM 每生成一个字就立刻发过来（SSE / WebSocket）
```

好处：
1. 用户看到实时打字效果，体验好
2. 工具调用的 JSON 积累完毕后立刻开始执行，不等后续文字
3. 如果任务明显跑偏，可以提前中断，不用等全部生成完

**底层协议**：HTTP SSE（Server-Sent Events）。服务器保持 HTTP 连接不关闭，持续推送事件，格式是：
```
data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello"}}

data: {"type":"content_block_delta","delta":{"type":"text_delta","text":" World"}}

data: [DONE]
```

**为什么用 SSE 而不是 WebSocket？**（高频追问，很多人答不清）

SSE 和 WebSocket 都能做"服务器持续推送"，但 LLM 流式几乎都选 SSE，原因：

| 维度 | SSE | WebSocket |
|------|-----|-----------|
| 方向 | **单向**（服务器→客户端） | 双向 |
| 底层 | 就是普通 HTTP 响应（`Content-Type: text/event-stream`）| 独立协议，需要 HTTP Upgrade 握手 |
| 基础设施 | 走标准 HTTP，代理/CDN/防火墙天然支持 | 中间设施常需特殊配置 |
| 复杂度 | 极简，浏览器原生 `EventSource` | 需要管理连接状态、心跳 |

LLM 流式的场景是"我发一个请求，你把答案一个字一个字推给我"——**这是纯单向推送**，不需要在生成过程中往服务器发数据。SSE 正好匹配这个模式，而且它本质就是一个"不关闭的 HTTP 响应"，复用现有 HTTP 基础设施，简单可靠。WebSocket 的双向能力在这里用不上，反而带来额外复杂度。所以除非有双向实时交互需求（如语音对话打断），LLM API 一律用 SSE。

**先补基础：token、上下文窗口、计费**

理解下面的 Prompt Cache 和第8章的上下文压缩之前，必须先搞懂三个基础概念——它们是整个 Agent 工程的"物理约束"：

- **token（词元）**：LLM 不按"字"处理文本，而按 token。token 是最小处理单位，可能是一个词、半个词、一个标点。经验值：英文约 1 token ≈ 4 字符，中文约 1 汉字 ≈ 1-2 token（所以中文更耗 token）。你发给模型的一切（system prompt + 历史 + 工具描述 + 输入）和模型生成的一切，都换算成 token。
- **上下文窗口（context window）**：模型单次能处理的 token 总数上限（"输入 + 输出"加起来）。这是**硬限制**——超了 API 直接报错（`prompt is too long`，见 2.7）。Agent 工程一大半功夫（压缩、记忆索引、工具延迟加载）都是在这个有限窗口里精打细算。
- **input vs output 计费不同**：input token（发过去的）单价低，但 Agent 每轮重发全部历史，累积是大头；output token（生成的）单价通常是 input 的 3-5 倍，但量小。**成本大头往往是 input**——这正是 Prompt Cache（缓存重复 input）和上下文压缩（减少 input）价值最大的原因。

---

## 2.4 StreamEvent：流事件的完整类型体系

```java
// llm/StreamEvent.java（sealed interface，所有子类型穷举）
public sealed interface StreamEvent {
    record TextDelta(String text) implements StreamEvent {}         // 文字片段来了
    record ThinkingDelta(String text) implements StreamEvent {}    // 思考片段来了
    record ThinkingComplete(String thinking, String signature) implements StreamEvent {}
    record ToolCallStart(String toolId, String toolName) implements StreamEvent {}  // 开始一个工具调用
    record ToolCallDelta(String partialJson) implements StreamEvent {}  // 工具参数的 JSON 片段
    record ToolCallComplete(String toolId, String toolName, Map<String, Object> arguments) implements StreamEvent {} // 工具调用完整了
    record StreamEnd(String stopReason, int inputTokens, int outputTokens,
                     int cacheReadTokens, int cacheCreationTokens) implements StreamEvent {}
    record Error(String message) implements StreamEvent {}
}
```

**工具调用的 JSON 是流式积累的：**

当 LLM 决定调用 `ReadFile` 工具时，它不是一次性输出完整 JSON，而是像打字一样流出来：

```
ToolCallStart("toolu_001", "ReadFile")
ToolCallDelta('{"file')
ToolCallDelta('_path"')
ToolCallDelta(': "/src/A')
ToolCallDelta('gent.java"}')
ToolCallComplete("toolu_001", "ReadFile", {"file_path": "/src/Agent.java"})
```

`AnthropicClient` 里用 `jsonAccum` 收集这些片段，`contentBlockStop` 事件触发时解析完整 JSON：

```java
var jsonAccum = new StringBuilder();

// 积累 JSON 片段
case delta.isInputJson():
    jsonAccum.append(delta.asInputJson().partialJson());

// 解析完整 JSON
case event.isContentBlockStop():
    Map<String, Object> args = MAPPER.readValue(jsonAccum.toString(), Map.class);
    queue.put(new StreamEvent.ToolCallComplete(toolId, toolName, args));
```

---

## 2.5 Prompt Cache：降低成本的关键技术

这是 CodePal 里一个非常重要但容易被忽视的设计。

**问题**：Agent 每轮都要把完整的 system prompt + 所有工具 schema + 历史消息发给 LLM。system prompt 可能几千 token，工具 schema 可能几千 token，这些内容每轮都重复发，每次都重新计费，成本爆炸。

**Prompt Cache 原理**：Anthropic 提供了缓存功能。你在某个位置加上 `cache_control: {"type": "ephemeral"}` 标记，API 会把这个标记之前的内容缓存 5 分钟。下一次请求如果前缀完全相同，就命中缓存，缓存命中的 token 费用只有正常费用的 10%。

**CodePal 的实现**：在三个位置打缓存标记：

```java
// 1. System Prompt 末尾打标记
var systemBlock = TextBlockParam.builder()
        .text(systemPrompt)
        .cacheControl(CacheControlEphemeral.builder().build())  // ← 标记
        .build();

// 2. 工具列表末尾（最后一个工具）打标记
if (isLast) {
    builder.cacheControl(CacheControlEphemeral.builder().build());  // ← 标记
}

// 3. 最后一条 user 消息末尾打标记
private void markLastUserTailForCache(List<MessageParam> messages) {
    // 找到最后一条 user 消息，在最后一个 content block 上打标记
}
```

**三处缓存的收益分析：**

```
每轮请求的 token 组成：
  ├── system prompt（几千 token）← 每轮完全相同，缓存命中率接近 100%
  ├── tool schemas（几千 token）← 工具列表基本不变，缓存命中率高
  ├── 历史消息（增量增长）      ← 老消息缓存命中，新消息要新计费
  └── 最新 user 消息           ← 每轮不同，不可缓存
```

实际使用中，一个长任务的 60-70% 的 input token 都能命中缓存，成本大幅降低。

**缓存命中的前提**：前缀内容字节完全一致。这就是为什么 `ContentReplacementState` 要保证工具结果内容在多轮之间字节稳定——任何变化都会导致缓存失效。

---

## 2.6 AnthropicClient 的消息构建

`buildMessages` 把 CodePal 内部的 `Message` 对象转换成 Anthropic SDK 需要的格式。

重点看三种特殊消息的处理：

**① assistant 消息带 tool_use**

```java
if ("assistant".equals(msg.getRole()) && (hasThinking || hasToolUses)) {
    var content = new ArrayList<ContentBlockParam>();
    // 顺序：ThinkingBlock → TextBlock → ToolUseBlock
    if (hasThinking) { content.add(ContentBlockParam.ofThinking(...)); }
    if (msg.getContent() != null) { content.add(ContentBlockParam.ofText(...)); }
    if (hasToolUses) {
        for (var tu : msg.getToolUses()) {
            content.add(ContentBlockParam.ofToolUse(...));
        }
    }
}
```

**② user 消息带 tool_result**

```java
} else if (msg.getToolResults() != null && !msg.getToolResults().isEmpty()) {
    var content = new ArrayList<ContentBlockParam>();
    for (var tr : msg.getToolResults()) {
        content.add(ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                        .toolUseId(tr.toolUseId())
                        .content(tr.content())
                        .isError(tr.isError())
                        .build()));
    }
}
```

**③ 连续相同角色消息合并**

```java
private List<MessageParam> mergeConsecutiveSameRole(List<MessageParam> messages) {
    // Anthropic API 要求消息角色严格交替（user/assistant/user/assistant...）
    // 如果出现两条连续 user 消息，合并成一条
}
```

Anthropic API 要求消息严格交替 user/assistant，如果有两条连续的 user 消息（比如工具结果后又加了一条 system reminder），需要合并，否则 API 报 400。

---

## 2.7 错误分类：精准处理不同类型的失败

```java
private LlmException classifyError(Exception e) {
    if (e instanceof com.anthropic.errors.UnauthorizedException)
        return new LlmException.AuthenticationException(...);    // 401：API Key 错误

    if (e instanceof com.anthropic.errors.RateLimitException)
        return new LlmException.RateLimitException(...);         // 429：限流

    if (e instanceof com.anthropic.errors.BadRequestException bre) {
        if (msg.contains("prompt is too long"))
            return new LlmException.ContextTooLongException(...); // 400：上下文超长
        return new LlmException("Bad request", bre);
    }

    if (e instanceof com.anthropic.errors.AnthropicIoException)
        return new LlmException.NetworkException(...);            // 网络错误
}
```

精准分类的意义：`agentLoop` 可以针对不同错误类型做不同处理：
- `ContextTooLongException` → 触发强制压缩后重试
- `RateLimitException` → 等待 5 秒后重试
- `AuthenticationException` → 直接退出，不重试（重试也没用）

---

## 2.8 模型支持扩展：OpenAI 兼容协议

```java
case "openai-compat" -> new OpenAiCompatClient(cfg, systemPrompt);
```

`OpenAiCompatClient` 使用 OpenAI 的 SDK，但 `baseUrl` 指向其他服务（如 DeepSeek、Moonshot、本地 Ollama 等），因为它们都实现了 OpenAI 兼容接口。

这是"适配器模式"的实际应用：不同厂商的 API，用同一套 `LlmClient` 接口封装，上层代码无感知。

---

## 2.9 面试官可能问的问题

**Q：你们的流式调用是怎么实现的？**

> 使用 Anthropic SDK 的 `createStreaming` 方法，底层是 HTTP SSE 长连接。SDK 返回一个 `StreamResponse`，我们遍历它的 iterator，每个事件（TextDelta、ToolCallDelta、StreamEnd 等）封装成 `StreamEvent` put 进 `BlockingQueue`。Agent 的内层循环从队列里 poll，超过 30 秒没收到事件就认为 stream 超时。两层 BlockingQueue 解耦了 HTTP IO 线程、Agent 处理线程和 TUI 渲染线程。

**Q：Prompt Cache 是怎么工作的？你们是怎么用的？**

> Anthropic 的 Prompt Cache 在请求里打 `cache_control: ephemeral` 标记，API 会缓存标记之前的内容 5 分钟，命中时只收 10% 费用。我们在三个位置打标记：system prompt 末尾、工具列表末尾、最后一条 user 消息末尾，覆盖三个最稳定的前缀。实际使用中 60-70% 的 input token 能命中缓存，长任务成本显著降低。

**Q：为什么要把 LlmClient 设计成接口？**

> 解耦。Agent 的核心逻辑和具体 LLM 提供商无关，今天用 Anthropic，明天换 OpenAI，或者接入开源模型的 OpenAI 兼容接口，只需改配置，不改代码。测试时也可以注入 Mock，不用真正调 API，节省成本。这是面向接口编程的经典应用。

**Q：为什么要两层 BlockingQueue？能不能合并成一层？**

> 两层队列解耦了三种不同节奏的线程：HTTP IO 线程（受网络速度支配）、Agent 处理线程（要解析事件、执行工具）、TUI 渲染线程（受终端刷新率支配）。第一层 `StreamEvent` 队列让 HTTP 线程只管把流事件塞进去就走，不被下游处理拖慢；第二层 `AgentEvent` 队列让 Agent 处理和 UI 渲染解耦，UI 卡一下不会阻塞 Agent 逻辑。合并成一层的话，任何一端慢了都会反压到另一端——网络抖动会让 UI 卡，UI 渲染慢会让网络读取停滞。分两层是"生产者消费者速度不匹配时用队列缓冲"的经典解法。

**Q：流式和非流式，对 token 计费有区别吗？**

> 没有区别。计费只看 input/output token 总量，跟"一次性返回"还是"流式逐字返回"无关——流式只是传输方式的差异。流式的价值在**体验和效率**（首字延迟低、工具 JSON 攒完就能提前执行、能提前中断），不在省钱。省钱靠的是 Prompt Cache 和上下文压缩。

**Q：stream 超时你们怎么判断？为什么是 30 秒？**

> 从 `StreamEvent` 队列 `poll(30, SECONDS)`——30 秒没等到任何新事件就判定 stream 卡死，发 ErrorEvent 交给 agentLoop 重试。注意判断的是"**事件间隔**"而非"总时长"：一个长回复可能生成好几分钟，但只要一直有 delta 流进来（间隔远小于 30s）就不算超时；真正要防的是"服务端挂了、连接僵死、再也不来数据"这种情况。30s 是经验值——正常生成的 token 间隔在毫秒级，30s 没动静基本就是异常了。

---

## 2.10 生产中可能遇到的问题

**问题1：Prompt Cache 失效，成本突然升高**

原因：历史消息里有任何字节变动，缓存前缀就断掉了。常见触发点：
- 工具结果包含时间戳或随机数（每轮不同）
- system reminder 里有动态内容

解法：仔细检查哪些内容会变化，把动态内容放在缓存标记之后（让它不参与缓存），静态内容放在标记之前。

**问题2：stream 中途断开，只收到半段回复**

原因：网络抖动、API 服务端超时。
解法：在 `poll(30, TimeUnit.SECONDS)` 里设置合理超时，超时时发送 `ErrorEvent`，`agentLoop` 里捕获后重试。同时记录当前已接收的 `text`，重试时带上"请从中断处继续"的提示。

**问题3：LLM 返回 JSON 格式的工具调用参数解析失败**

原因：模型偶尔生成格式有误的 JSON（多余引号、截断等）。
```java
try {
    args = MAPPER.readValue(jsonAccum.toString(), Map.class);
} catch (Exception e) {
    args = new HashMap<>();  // 容错：解析失败给空参数
}
```
CodePal 的处理是解析失败时给空 Map，工具执行时再校验参数是否完整，避免整个 Agent 崩掉。

**问题4：连续相同角色消息导致 API 400**

Anthropic 要求 user/assistant 严格交替。当 Agent 添加多条 system reminder 时，可能出现连续 user 消息。`mergeConsecutiveSameRole` 处理了这个问题，但写其他框架时要注意这个 API 约束。

**问题5（用户视角）：流式输出到一半，用户按了 Ctrl+C，已消耗的 token 算钱吗？半截回复要不要存历史？**

算钱——已经生成的 output token 已经产生费用，中断只是停止继续生成。至于半截回复要不要存进对话历史，是个设计选择：存的话，下一轮模型能看到"我上次说到一半被打断了"，语境连贯；不存的话，历史干净但丢失了那段内容。CodePal 的做法是**保留已接收的部分并标记为被中断**，既让费用没白花（内容还在），也让模型知道上次的话没说完。这个细节直接关系用户对"中断"的心理预期——用户按 Ctrl+C 时希望"干净停下"，但不希望"我刚才等的半天全没了"。

**问题6（用户视角）：网络差的用户，流式一顿一顿的，体验反而比非流式还糟**

流式在网络好时是"打字机"顺滑效果，但网络差时会变成"卡一下蹦几个字、再卡一下"，观感割裂。有些用户会觉得比"转圈等 10 秒然后一次性出全文"更烦躁。

解法：客户端可以做**平滑输出**——收到的 delta 先进本地缓冲，按固定节奏（如每 20ms 吐几个字）渲染，把网络抖动"熨平"成均匀的打字效果。这是很多成熟产品在做的体验优化，属于"流式的最后一公里"。

---

*下一章：工具系统 — Agent 的"手"是怎么设计的*
