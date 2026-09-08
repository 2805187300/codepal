# 第17章：回顾与展望 — 三层工程方法论

> 目标：把整本书的知识串成一个体系，理解 Prompt Engineering / Context Engineering / Harness Engineering 三层方法论，以及"下一代 Agent 工程"的发展方向。

---

## 17.1 三层方法论：包含关系，不是并列

整个 CodePal 的设计可以用三层来概括：

```
┌─────────────────────────────────────────────────────────┐
│  Harness Engineering（最外层）                           │
│  管"整个系统怎么运转"                                   │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Context Engineering（中间层）                   │   │
│  │  管"给 LLM 什么信息"                            │   │
│  │  ┌─────────────────────────────────────────┐   │   │
│  │  │  Prompt Engineering（最内层）            │   │   │
│  │  │  管"怎么跟 LLM 说话"                    │   │   │
│  │  └─────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

类比：Prompt 是"油门指令"，Context 是"燃油"，Harness 是"整辆车"。光踩油门加燃油，没有方向盘和刹车，车是要出事的。

---

## 17.2 Prompt Engineering：怎么说

**定义**：管"怎么跟 LLM 说话"——指令的结构化、清晰度、格式。

**CodePal 里的体现**：

| 位置 | 作用 |
|------|------|
| `PromptSections.DOING_TASKS_CONTENT` | 告诉 LLM 如何处理任务（先读再改、不要过度设计）|
| `PromptSections.EXECUTING_ACTIONS_CONTENT` | 告诉 LLM 危险操作要停下来确认 |
| `SUMMARY_SYSTEM_PROMPT`（第8章）| 9 章结构化要求，决定压缩质量 |
| 每个工具的 `description()` | 告诉 LLM 什么时候用这个工具、怎么填参数 |
| Fork Boilerplate（第13章）| 约束 fork Agent 的行为边界 |

**核心经验**：Prompt 写得好，效果会改善；但改善是有天花板的。写得再好也无法防止 LLM 偶尔"跑偏"，无法保证工具结果一定正确，无法解决上下文超限的问题。这些需要 Harness 层来解决。

**评测驱动的 Prompt 优化（第8章的故事）**：

SUMMARY_SYSTEM_PROMPT 从"请总结对话"改成"分9章输出，每章明确要求"，信息保留率从 17% 到 100%，代码零改动。这是 Prompt Engineering 最大价值的体现——在正确的位置，改对 prompt，效果可以质变。

---

## 17.3 Context Engineering：给什么信息

**定义**：管"给 LLM 什么信息"——把正确的、及时的上下文送进 LLM 的输入窗口。

**CodePal 里的体现**：

| 机制 | 解决的问题 |
|------|-----------|
| `detectEnvironment()`（第5章）| 注入 OS/Shell/Git 分支，LLM 生成正确命令 |
| `injectLongTermMemory()`（第4章）| 注入项目指令和长期记忆 |
| `MemoryManager.buildSystemReminder()`（第9章）| 注入 MEMORY.md 索引，LLM 知道有什么历史记忆 |
| `memoryRecallFuture`（非阻塞召回）| 并行召回相关记忆，不增加延迟 |
| Layer 1 落盘 + Layer 2 摘要（第8章）| 压缩上下文，保留关键信息，腾出空间 |
| MCP 延迟加载（第7章）| 按需暴露工具描述，节省 85% 工具描述 token |
| Recovery Attachment（第8章）| 压缩后恢复文件快照，保持工作状态 |

**Context Engineering 的本质**：Token 是有限的。每个 token 都是决策材料，应该尽量放"有用的"、删"没用的"。好的 Context 工程让 LLM 在有限的窗口里看到最相关的信息，坏的 Context 让 LLM 看到一堆噪声，决策质量自然下降。

---

## 17.4 Harness Engineering：怎么运转

**定义**：管"整个系统怎么运转"——在 LLM 外围建确定性的约束、反馈和验证机制。

这是让 Agent 真正"可信赖"的关键层，也是 CodePal 花最多精力的地方。

**CodePal 里的体现**：

| 机制 | 解决的问题 |
|------|-----------|
| Agent Loop 的三种自愈（第4章）| context 超长/rate limit/max_tokens 自动恢复 |
| 工具并发安全分批（第3章）| 并发与安全性同时保证，读写语义一致 |
| 五层权限系统（第6章）| 危险操作拦截，弹窗从 30 次降到 5 次 |
| `isError` 区分错误和失败（第3章）| 工具执行失败时告知 LLM 而不是崩溃 |
| `tool_use/tool_result` 配对保护（第8章）| 压缩时保证 API 协议合法性 |
| `finally` 块保证 LoopComplete（第4章）| TUI 不会因 Agent 异常而卡死 |
| maxIterations 保护（第4章）| 防止死循环 |
| 评测框架（第8章）| 量化验证，让优化有数据支撑 |
| Metrics + Trace（第8章）| 生产可观测性，定位性能瓶颈 |

**Mitchell Hashimoto 的核心观点（值得背下来）**：

> "每次 Agent 犯错就工程化一个永久修复"

这抓住了 Harness Engineering 的本质：不是让 LLM"不犯错"（那不可能），而是在 LLM 犯错时，框架能捕获、恢复、防止损害。每一个"加权限拦截"、"加错误信息回传"、"加工具输入校验"都是一次 Harness 层的改进。

---

## 17.5 CodePal 8 条亮点 vs 三层方法论的映射

| 亮点 | 层次 | 核心工程决策 |
|------|------|------------|
| 工具并发安全分批 | Harness | 工具自声明并发属性，批次间强等待 |
| MCP 工具延迟加载 | Context | 只注入工具名列表，按需加载 schema |
| 两层上下文压缩 | Harness + Context | Layer1 本地落盘，Layer2 LLM 摘要，Recovery 恢复 |
| 五层安全隔离 | Harness | 从白名单到规则到模式矩阵的防御纵深 |
| 评测驱动优化 | Harness | 量化评测 → 定位 → 修复 Prompt 问题 |
| 多 Agent 并行协作 | Harness | FileMailBox + Worktree 的分布式 Agent 系统 |
| Java 工程化专项 | Context + Harness | 结构化输出 + 自动格式转换，降低误操作 |
| 可观测性体系 | Harness | AtomicLong 并发安全统计，支撑调优决策 |

---

## 17.6 下一步：Agent 工程的发展方向

学完 CodePal，你已经掌握了一个"现代 Coding Agent"的完整技术栈。以下是这个领域正在演进的方向，面试时能说出来会加分：

**1. 更长的上下文窗口 vs 更好的压缩策略**

Gemini 1.5 Pro 支持 2M token，Claude 3.5 支持 200K，未来可能更大。但"放得下"不等于"用得好"——LLM 在超长上下文中的注意力会分散（"lost in the middle"问题）。好的压缩策略仍然有价值，不会因为窗口变大而消失。

**2. Agent 评测成为基础设施**

SWE-bench-Live 这样的持续更新评测集（每月新题）将成为评估 Agent 的标配，就像 LLM 评测的 MMLU。企业内部也会建立自己的业务场景评测集，驱动 Agent 质量改进。

**3. 多模态工具**

目前 CodePal 的工具都是文字输入输出。未来 Agent 会有"看截图"、"画架构图"、"操作 UI" 的能力（computer use）。Tool 接口需要扩展支持图像输入和结构化视觉输出。

**4. 更细粒度的 Harness 指标**

当前 Metrics 只到工具级（每个工具的调用次数、延迟、错误率）。下一步是 Token 级（每个工具调用消耗的 token）、Decision 级（LLM 每次决策的质量评分），用来量化"哪些工具 schema 写得不好导致 LLM 频繁调用错工具"这类问题。

---

*下一章（最终章）：面试求职全攻略*
