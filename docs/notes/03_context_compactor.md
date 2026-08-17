# 模块三：两层渐进式上下文压缩

> 对应简历亮点：第 3 条 — 两层渐进式上下文压缩
> 关键文件：`src/main/java/com/codepal/compact/ContextCompactor.java`、`src/main/java/com/codepal/compact/RecoveryState.java`

---

## 1. 问题背景

Agent 长任务每轮堆积消息，LLM 有上下文窗口硬上限（Claude 200K tokens）。超限 API 直接拒绝，Agent 崩掉。同时 token 越多每次调用越贵。

核心问题：**在不丢失关键信息的前提下，持续压缩对话历史。**

---

## 2. 两层防御架构

```
Layer 1（轻量）：工具结果太大 → 落盘，只留路径引用    ← 每轮必跑，纯本地操作
Layer 2（重量）：接近上下文上限 → 调 LLM 生成摘要     ← 按需触发
```

---

## 3. Layer 1：工具结果落盘（offloadAndSnip）

**两个触发阈值：**
- 单条工具结果 > 50000 字符
- 一条消息内所有结果之和 > 200000 字符

超过阈值：把结果写到 `.codepal/tool_results/<toolUseId>` 文件，历史里替换为：
```
[Result of N chars saved to .codepal/tool_results/toolu_001]
```

**已处理判断**：内容以 `[Result of` 开头则跳过，防止重复处理。

即时落盘也在 `StreamingExecutor.executeSingle` 里执行（10000 字符阈值），是 Layer 1 的前置拦截。

---

## 4. Layer 2：LLM 生成摘要（autoCompact）

### 触发时机：软触发 + 硬触发

```
有效窗口 = contextWindow − min(maxOutput, 20000)
软触发线 = 有效窗口 − 13000   ← 达到时尝试压缩，允许失败
硬触发线 = 有效窗口 − 3000    ← 达到时强制压缩，必须成功
```

减去 `maxOutput` 是因为摘要请求本身要消耗输出 token，需要预留。

### Token 估算：锚点 + 增量

```
每次 LLM 返回 → 记录真实 token 数（锚点）+ 当时的消息数量
下次估算 = 锚点真实值 + 锚点之后新消息的字符估算
```

字符估算公式：`字符数 / 3.5`（英文约 4 字符/token，中文约 2 字符/token，取均值）。

锚点解决了 Prompt Cache 命中时纯字符估算严重低估的问题。

### computeKeepStartIndex：合法切断点

从最后一条消息往前，累计 token，找到切断点：
- 累计 ≥ 10000 token 或 ≥ 5 条消息 → 停止（下限）
- 累计 > 40000 token → 停止（上限）

**配对保护**：切断点若落在 tool_result 消息上，往前移一步到对应的 assistant tool_use 消息，保证 tool_use/tool_result 配对完整，防止 API 400 错误。

### 生成摘要

把老旧部分序列化，连同 `SUMMARY_SYSTEM_PROMPT` 发给 LLM。

SUMMARY_SYSTEM_PROMPT 要求分 9 章输出：
1. 用户原始意图
2. 关键技术概念
3. 具体文件和代码片段
4. 错误和修复过程
5. 问题解决情况
6. 所有用户消息
7. 待办任务
8. 当前工作
9. 下一步

**这个 prompt 的质量直接决定信息保留率**（见评测驱动优化亮点）。

### PTL 重试

如果连摘要请求本身都超长，从最老的 API 轮次（tool_use/tool_result 为一组）开始丢弃，缩短后重试，最多 3 次。

### 压缩后对话结构

```
[摘要消息（user）]  ← 包含摘要文字 + Recovery Attachment
[近期消息 × N]      ← 原样保留（近期 5 条或 10000 token）
```

---

## 5. Recovery Attachment：记忆恢复包

压缩摘要后附加，包含四部分：

```
## Recently read files   ← 最近读过的文件内容快照（最多 5 个，每个 5000 token）
## Active skills         ← 激活过的 Skill 的 SOP 内容
## Available tools       ← 可用工具列表
## Note                  ← 说明是重建上下文，精确内容需重读源文件
```

快照由 `RecoveryState` 记录，`StreamingExecutor.snapshotForRecovery` 在每次 ReadFile 成功后存入，以文件路径为 key（同一文件只保留最新版本）。

`RecoveryState` 用 `synchronized` 保护，因为并发批次的多个 ReadFile 可能同时写入。

---

## 6. 断路器（Circuit Breaker）

Layer 2 软触发连续失败 3 次 → 断路器跳闸 → 之后不再尝试软触发，只保留硬触发。

防止每轮都尝试压缩、每次都失败、每次都重试，浪费 token 和时间。

---

## 7. 整体流程

```
每轮 agentLoop
  → Layer 1: offloadAndSnip（每轮必跑）
  → 估算 token（锚点 + 增量）
  → < 软触发线：跳过
  → 软触发线 ≤ token < 硬触发线：autoCompact（断路器未跳闸时）
  → token ≥ 硬触发线：forceCompact（强制）
```

---

## 8. 面试标准答法

**Q：上下文压缩是怎么设计的？**

> 两层防御。Layer 1 轻量，每轮必跑：工具结果超阈值即时落盘，历史只留路径引用，防单条大结果撑爆。Layer 2 按需触发：估算 token 接近窗口上限时，把老旧历史调 LLM 生成结构化摘要替换，近期消息原样保留，附带 Recovery Attachment 帮模型快速恢复上下文。压缩边界严格对齐 tool_use/tool_result 配对约束防 400 错误，连摘要请求本身超长时用 PTL 重试兜底。

**Q：信息保留率 17% → 100% 是怎么做到的？**

> 评测触发：设计专项测试，开头注入 6 条关键信息，撑满上下文触发压缩，问模型还记得几条，第一次只记住 1 条（17%）。定位：排查发现 SUMMARY_SYSTEM_PROMPT 太简单，只说"总结对话"，没有结构化要求。修复：改写 prompt 要求分 9 章输出，明确每章需保留的信息类型。结果：重跑评测 6 条全记住（100%），代码零改动。核心结论：Agent 很多问题本质是 prompt 问题，评测是定位问题的唯一可靠手段。

---

## 9. 面试深度追问全景

### 设计决策类

**Q：为什么要分两层？合并成一层不行吗？**

> 两层处理的问题性质不同，合并会互相干扰。第一层处理**局部超大**：一个工具结果就 5 万字，但历史总量还没到压缩线，合并到第二层会浪费这期间的 token。第二层处理**整体累积**：每条消息都正常大小，积累多了总量超限。第一层无脑运行、零 LLM 成本；第二层有调用成本，应该尽可能少触发。

**Q：Token 估算为什么用字符数 / 3.5 而不精确计算？**

> 精确 tokenize 需要调用 tokenizer，有 CPU 开销且依赖具体模型。3.5 是英文/代码场景的经验值，用于触发决策，误差 ±20% 完全可接受，所以留了 13K token 的安全边距来吸收误差。（`estimateTokens` 方法，第 299-329 行）

**Q：为什么保留尾部而不是全量摘要？**

> 全量摘要有两个问题：一是正在进行中的 tool_use/tool_result 对会被打断，API 拒绝；二是丢失当前上下文会让 LLM 在下一轮"失忆"，需要重新找状态。保留最近 5 条以上原始消息（`MIN_KEEP_MESSAGES = 5`）让 LLM 继续原来的操作。

**Q：`KEEP_RECENT_TOKENS = 10_000` 和 `KEEP_MAX_TOKENS = 40_000` 这两个参数怎么定的？**

> 经验值 + Eval 调优。10K 是下限：低于这个量保留内容太少，LLM 无法从摘要+尾部重建足够上下文；40K 是上限：保留太多导致压缩比不够，摘要后仍接近窗口上限，下一轮马上又触发。

---

### 边界条件类

**Q：tool_use / tool_result 配对约束能详细说说吗？**

> Anthropic API 规定：assistant 消息含 `tool_use` block，则紧跟的 user 消息必须含对应 `tool_result`（通过 `tool_use_id` 匹配），配对不完整直接返回 400。
>
> 压缩时的风险：如果保留窗口起点是一条只含 `tool_result` 的 user 消息，对应的 `tool_use`（在 assistant 消息里）被摘要掉了，形成孤儿配对。代码里通过 while 循环修复（`computeKeepStartIndex` 第 457 行）：
> ```java
> while (keepStart > 0 && isToolResultMessage(messages.get(keepStart))) {
>     keepStart--;  // 往前移，直到边界落在 assistant 消息上
> }
> ```

**Q：如果整个会话都是密集的 tool_use/tool_result，保留窗口移不过去怎么办？**

> `computeKeepStartIndex` 返回 0 或小于 `MIN_KEEP_MESSAGES` 时，`autoCompact` 直接返回空字符串放弃本次压缩（第 485 行的 degenerate case 判断）。极端情况下会触发硬触发线的 `forceCompact`。

**Q：摘要请求本身如果 Token 超出 LLM 限制怎么办？**

> PTL（Prompt Too Long）重试：把要摘要的前缀按 API 轮次分组，从最老的组开始丢弃，每次丢弃约 1/5 的估算 token 量，最多重试 3 次。3 次都失败则向上抛异常，Circuit Breaker 记录失败。（第 730-754 行）

**Q：Circuit Breaker 熔断了之后会怎样？会不会永久熔断？**

> 熔断只阻止**软触发**（`tracking.isTripped()` 判断在第 231 行），硬触发线不受影响。一旦有一次成功就 `reset()`，不是永久熔断。目的是避免 LLM 摘要服务短暂不可用时每轮都浪费一次失败请求。

---

### 工程实现类

**Q：落盘文件的路径怎么管理？会不会冲突？**

> 用 `tool_use_id` 作为文件名，这个 ID 是 LLM 生成的全局唯一标识（类似 `toolu_01abc...`），天然不冲突。目录是 `.codepal/tool_results/`，用 `CREATE_NEW` 写入模式——文件已存在说明已落盘，直接返回已有路径，不重复写。（`writeSpill` 方法，第 648-660 行）

**Q：Token 估算的 `UsageAnchor` 是什么？为什么需要它？**

> 纯字符估算在 Cache Hit 场景下会严重高估：第 2 轮命中 Anthropic Prompt Cache，实际计费 token 极少，但字符估算把全部历史都算进去，误判为"快满了"，提前触发不必要的压缩。
>
> `UsageAnchor` 在每次流式响应结束后从 API 返回的 `usage` 字段中捕获真实 token 数，以及当时的消息条数作为锚点。之后新增的消息用字符估算叠加在真实值上，误差只在增量部分。（第 270-296 行）

**Q：Recovery Attachment 是什么？压缩后 LLM 怎么找回之前读过的文件？**

> 压缩后的摘要消息末尾附一个恢复区块，包含：
> 1. 最近读过的文件快照（最多 5 个，每个最多 5K tokens）
> 2. 激活的 skill 列表（最多 25K tokens 总量）
> 3. 可用工具列表（名称+单行描述）
>
> LLM 压缩后立刻能看到"我之前在看哪些文件"，不需要重新调工具读取。这是尽力而为的快照，注释里明确写了"For exact code, re-read the source rather than guess from the summary"。

**Q：会话恢复（resume）怎么用到压缩边界？**

> 每次压缩成功后调用 `SessionManager.saveCompactBoundary()`（第 510 行），把摘要文本和保留的尾部消息持久化到会话日志（`.jsonl`）。Resume 时不需要重放整个历史，直接从 `compact_boundary` 记录重建对话状态。

---

### 效果量化类

**Q：你说"单次会话可持续数小时"，有没有具体数据？**

> 以 200K token 窗口为例，长时编码会话平均每轮约 2K tokens 增量，不压缩约 100 轮（1-2 小时）就满了。第一层落盘把超大工具结果从 50K 降到 50 字符的引用；第二层把 100+ 轮历史压缩成约 5-10K 的摘要。理论上会话可无限延续，受限的只是磁盘空间。

**Q：Token 成本怎么降低的？**

> 两个维度：一是**输入 token 减少**——每轮发给 LLM 的上下文变小；二是**Prompt Cache 命中率提升**——压缩后的摘要文本是稳定的前缀，更容易命中 Anthropic Prompt Cache，计费降至约原价 1/10。

---

### 对比/扩展类

**Q：为什么不用 RAG（向量检索）来代替摘要？**

> RAG 对语义相似度检索很强，但 Agent 的工作上下文不是"检索历史"，而是"维持连续操作状态"。比如"刚才修改了哪几个文件、遇到了什么报错"，这是顺序信息，不是语义检索的场景。LLM 摘要更适合保留**操作流水线上下文**。两者可以互补：RAG 适合做长期知识库，摘要适合做当前会话记忆。

**Q：和 OpenAI Assistants API 的 Thread 机制比，有什么区别？**

> OpenAI Thread 的压缩策略是黑盒托管，开发者不可控。这套方案完全在应用层，好处是：可控制压缩时机（安全边距）、保证协议约束（配对完整性）、在摘要里附加恢复信息、持久化到本地做 resume。代价是需要自己维护逻辑正确性。

---

*下一模块：MCP 工具延迟加载（McpManager.java、ToolSearchTool.java）*
