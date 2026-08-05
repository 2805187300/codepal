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

*下一模块：MCP 工具延迟加载（McpManager.java、ToolSearchTool.java）*
