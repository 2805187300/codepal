# 第18章：面试求职全攻略

> 把 8 条简历亮点的面试话术全部整合，配上高频追问的标准答法，附加大厂 AI/Agent 岗位的常见考察维度。

---

## 18.1 简历怎么写：8 条亮点的精炼版本

这是写进简历的版本（每条 1-2 行，量化数据）：

> **工具并发安全分批执行**：设计"相邻同性质合并"分批算法，READ 类工具并发执行，WRITE/COMMAND 保持串行，批次间强等待保证语义一致性，**读密集场景效率显著提升**。
>
> **MCP 工具延迟加载（ToolSearch 索引层）**：百级 MCP 工具场景下，仅在模型搜索时按需加载单个工具 schema，**工具描述 Token 占用减少 85%**。
>
> **两层渐进式上下文压缩**：Layer 1 工具结果即时落盘，Layer 2 LLM 生成结构化摘要，压缩边界严格对齐 Function Calling 配对约束，**单次会话可持续数小时不丢失关键上下文**。
>
> **五层安全隔离**：规则白名单、危险模式检测、路径沙箱、YAML 规则文件、allow-always 会话记忆协同，**权限弹窗从平均 30 次降至 5 次以内**，可选接入 OS 级沙箱（macOS seatbelt / Linux bwrap）。
>
> **评测驱动优化**：搭建 EvalRunner 框架接入 SWE-bench-Live，自研上下文信息保留率测试，**通过改写 SUMMARY_SYSTEM_PROMPT 将信息保留率从 17% 提升至 100%，代码零改动**。
>
> **Lead-Teammate 多 Agent 并行协作**：Lead 分解任务后并行派发，各 Teammate 在独立 git worktree 内执行，FileMailBox 异步消息通道汇报进度，**大型重构效率提升约 60%**。
>
> **Java 工程化专项工具**：自动探测构建系统（pom.xml→Maven / build.gradle→Gradle），结构化提取编译错误，支持 class/method 级 JUnit 定向执行，**首轮编译修复成功率提升，定向测试执行时间缩短至全量运行的 1/N**。
>
> **可观测性体系**：工具执行出口和 Loop 完成点埋点，采集工具调用成功率、执行延迟、每轮 Token 消耗，支持 Session 级 Trace 追踪，`/metrics` 命令实时查看，**为上下文压缩策略和工具超时参数的量化调优提供数据依据**。

---

## 18.2 必背的 5 道核心面试题

### Q1：完整描述你的 Agent Loop 设计

**标准答法（60秒版）：**

> 每轮先检查上下文 token，触发两层压缩（大工具结果落盘 + LLM 摘要）。然后把对话历史、系统指令、工具列表发给 LLM，流式接收响应，边收边推给 TUI 显示。响应结束后判断 stop_reason：end_turn 且无工具调用就发 LoopComplete 退出；有工具调用就按并发安全性分批执行，结果写入历史继续下一轮。三种自愈：context 超长强制压缩重试，rate limit 等 5 秒重试，max_tokens 分三级处理（升级上限→续写→放弃）。finally 块保证无论何种原因退出，LoopComplete 一定发出，TUI 不卡死。

### Q2：你的工具并发设计怎么保证和串行语义一致？

**标准答法：**

> 两个不变量：并发批内部全是 READ 工具，按定义不修改外部状态，多个并发不干扰；批次间强等待（try-with-resources ExecutorService），WRITE 执行前上一批全部完成，WRITE 后的 READ 看到写后状态。任意读写序列，分批执行与全串行等价——这是形式上可证明的，不是凭感觉猜的。

**追问：两个 READ 工具读同一个文件，并发有问题吗？**

> 没问题。单次 ReadFile 是原子只读 IO，并发不干扰。如果同一轮既 ReadFile 又 WriteFile 同一个文件，分批后 ReadFile 在并发批、WriteFile 在串行批，模型给出的顺序保证了"读后写"，不会冲突。

### Q3：上下文压缩的边界保护是怎么做的？

**标准答法（这是面试里最能拉开差距的点）：**

> 压缩时要找合法切断点——不能把 tool_use/tool_result 配对拆开，否则 Anthropic API 报 400。`computeKeepStartIndex` 里在确定 keepStart 后，有一个 `while` 循环：如果切断点落在 tool_result 消息上，就往前移一步包含对应的 tool_use，保证配对完整。这是上下文压缩里最容易出 bug 的地方，真正踩过这个坑之后才会知道要加这个保护。

### Q4：五层权限系统是什么？弹窗从 30 次降到 5 次的核心机制？

**标准答法：**

> 五层顺序执行：Plan Mode 特殊处理 → 安全命令白名单快速放行 → 危险模式正则直接拒绝 → 受保护路径永久禁写（防权限提升攻击）→ 路径沙箱 → YAML 规则文件 → allow-always 会话记忆 → 模式矩阵兜底。
>
> 弹窗减少靠三点：白名单覆盖了 `ls`/`git status`/`grep` 等高频只读命令，自动放行；用户选"以后不再询问"后，当次会话内同条命令直接放行；ACCEPT_EDITS 模式下读写自动允许，只有执行命令才弹。三层叠加，绝大多数重复操作不打扰用户。

### Q5：你们的评测体系是怎么设计的？17%→100% 是怎么做到的？

**标准答法：**

> EvalCase 定义用例（prompt + 四种验证：文件存在检查、关键词检查、Bash 命令验证、组合）；EvalRunner 跑独立 Agent 实例，消费事件队列，依次验证，汇总报告。
>
> 信息保留率测试：开头注入 6 条关键信息，多轮工具调用撑满上下文触发压缩，最后问模型还记得几条。首次跑只记住 1/6（17%），排查发现 SUMMARY_SYSTEM_PROMPT 太模糊，只说"总结对话"，LLM 随意取舍。改成 9 章结构化要求，明确每章要保留的信息类型，重跑 6/6（100%），代码零改动。核心结论：Agent 很多"能力问题"本质是 prompt 问题，评测是唯一可靠的定位手段。

---

## 18.3 高频追问题库

**关于 LLM / API 的追问：**

Q：Prompt Cache 是怎么工作的？你们怎么用的？  
A：Anthropic 在请求里打 `cache_control: ephemeral` 标记，缓存前缀 5 分钟，命中只收 10% 费用。我们在 system prompt 末尾、工具列表末尽、最后一条 user 消息末尾三处打标记。长任务中 60-70% 的 input token 命中缓存。

Q：token 估算为什么不直接用字符数/3.5？  
A：Prompt Cache 命中时，API 报告的实际 input_tokens 远小于字符估算（缓存部分不重新计费）。纯字符估算会高估实际用量，导致提前触发压缩。锚点机制用真实 API 报告值做基准，只对新消息做字符估算，精度高很多。

**关于并发的追问：**

Q：Virtual Thread 和线程池有什么区别？  
A：Virtual Thread 遇到阻塞 IO 自动挂起释放 Carrier Thread，不占用 OS 线程。线程池固定线程数，IO 阻塞时白占资源。Agent 大量时间等 LLM 返回，Virtual Thread 完美适配。`newVirtualThreadPerTaskExecutor()` + try-with-resources 实现批次间强等待，代码简洁语义清晰。

Q：ConcurrentHashMap 和 HashMap 的区别？  
A：ConcurrentHashMap 是线程安全的，内部用分段锁（Java 8 后改为 CAS + synchronized），并发读不加锁，并发写只锁相关段。MetricsCollector 里用它因为多个并发工具执行线程同时写统计数据，普通 HashMap 会有数据竞争。

**关于 Agent 架构的追问：**

Q：你们有用 RAG 吗？  
A：没有，Code Agent 场景不适合 RAG。代码是结构化的，grep 精确命中比向量相似度更准；代码一直在改，向量索引会过期；理解代码需要顺藤摸瓜（链式读取），RAG 做不到。我们用工具驱动的实时检索：ReadFile + Grep + Glob，本质也是"检索加生成"，只是检索用工具调用而非向量搜索。

Q：怎么防止 Agent 进入死循环？  
A：三层保护：maxIterations 硬上限（超过报错退出）；权限系统拦截越权操作，返回错误让 LLM 知道"这条路走不通"；工具超时保护（BashTool 最长 600s），防止单个命令卡死整个循环。

Q：Skill、MCP、Function Calling 三者关系？  
A：Function Calling 是底层协议（LLM 输出 tool_use，Agent 执行，结果回传）。MCP 扩展工具集（解决"能做什么"，把外部服务包装成工具）。Skill 扩展行为模式（解决"该怎么做"，本质是 prompt 配置加工具过滤，不提供新工具能力）。类比：Function Calling 是嘴手连接方式，MCP 是往工具箱加工具，Skill 是给 Agent 一份任务手册。

---

## 18.4 大厂 AI/Agent 岗位常见考察维度

**字节跳动：**
- 重点考 LLM 工程化落地（不是算法，是工程）
- 追问"这个设计的性能数据是多少"——要有量化
- 考察大规模系统下的稳定性（rate limit、failover、监控）

**阿里巴巴：**
- 偏重 RAG + Agent 结合（阿里内部大量 RAG 实践）
- 会问"你们的 RAG 为什么没用向量数据库"——要能说清取舍
- MCP 协议了解程度（阿里在积极跟进 MCP 生态）

**腾讯：**
- 更重视安全（你的权限系统设计刚好契合）
- 会问"Agent 有什么安全风险，你们怎么防"——五层权限 + OS 沙箱是标准答案
- 追问 Prompt Injection 防御

**通用 AI 岗位追问：**
- "你认为 Harness Engineering 和 Context Engineering 哪个更重要？"
  → 不是哪个更重要，是分工不同。Harness 决定可靠性上限，Context 决定效果上限，两者缺一不可。CodePal 的经验：先把 Harness 做扎实，Agent 才能稳定运行；在稳定的基础上，Context 优化才能体现效果。
- "SWE-bench 已经被很多团队跑过了，你们用 SWE-bench-Live 的原因？"
  → 原版数据污染严重（OpenAI 内审发现超 60% 题目有缺陷），frontier 模型能根据任务 ID 直接复现答案。SWE-bench-Live 每月新题，2026 年的题不在任何训练数据里，评测结果更可信。

---

## 18.5 项目整体介绍（1分钟版本）

面试自我介绍环节，项目部分怎么讲：

> CodePal 是我用 Java 21 从零实现的 AI Coding Agent 框架，对标 Claude Code 的核心架构。在终端里运行，接受自然语言指令，通过 Function Calling 驱动工具调用，支持多轮对话、上下文管理和多 Agent 协作。
>
> 核心难点有几个：
> 一是上下文管理，两层压缩保证长任务不超限，同时通过评测发现并修复了压缩摘要 prompt 的问题，信息保留率从 17% 提升到 100%；
> 二是工具调度，设计了并发安全分批算法，读密集场景效率显著提升；
> 三是安全性，五层权限系统将权限弹窗从 30 次降到 5 次以内；
> 四是多 Agent 协作，Lead-Teammate 架构加上 git worktree 文件隔离，大型重构效率提升约 60%。
>
> 整个项目的设计理念是 Harness Engineering：不是让 LLM 不犯错，而是在犯错时框架能捕获、恢复、防止损害。

---

## 18.6 提问环节的好问题

面试到最后面试官问"你有什么问题要问我"，可以问：

1. "你们的 Agent 框架主要解决什么业务场景？是 Coding Agent 还是其他领域？"（表明你对 Agent 有领域认知）
2. "你们怎么评估 Agent 的效果？有自己的评测集吗？"（表明你重视量化评估）
3. "多 Agent 协作这块有什么做的方向？"（表明你对技术演进有思考）

---

*全书完*

---

## 附录：章节索引

| 章节 | 主题 | 对应亮点 |
|------|------|---------|
| 第1章 | 初识 Coding Agent | — |
| 第2章 | LLM 客户端与流式调用 | — |
| 第3章 | 工具系统 | 亮点1 |
| 第4章 | Agent Loop 主循环 | 所有亮点基础 |
| 第5章 | System Prompt 设计 | — |
| 第6章 | 五层权限系统 | 亮点4 |
| 第7章 | MCP 协议与延迟加载 | 亮点2 |
| 第8章 | 上下文压缩、评测、可观测性 | 亮点3、5、8 |
| 第9章 | 记忆系统 | — |
| 第10章 | Slash Command | — |
| 第11章 | Skill 系统 | — |
| 第12章 | Hook 系统 | — |
| 第13章 | SubAgent | 亮点6基础 |
| 第14章 | Worktree | 亮点6基础 |
| 第15章 | Agent Teams | 亮点6 |
| 第16章 | Java 工程化专项工具 | 亮点7 |
| 第17章 | 三层工程方法论回顾 | 综合 |
| 第18章 | 面试求职全攻略 | 全部 |
