# CodePal 简历技术亮点（8 条）

> 直接对应简历「技术亮点」栏，遵循 STAR 原则：背景（为什么做）→ 行动（怎么做）→ 结果（量化数据）。
> 同时标注学习路线，建议按表格顺序逐条啃代码，每条学完在 `docs/notes/` 下写一篇笔记。

---

- **工具并发安全分批执行**：Agent 执行多工具调用时全量串行导致读密集任务耗时过长，设计「相邻同性质合并」分批算法，每个工具自声明并发属性，只读工具（ReadFile / Grep / Glob）合并入并发批次同时执行，写操作（EditFile / WriteFile / Bash）保持串行；批次间强等待保证任意读写序列与串行语义一致，**读密集场景执行效率显著提升，同时保证结果确定性**

- **MCP 工具延迟加载**：百级 MCP 工具全量注入上下文时工具描述自身消耗大量 Token，设计 ToolSearch 索引层，仅在模型主动搜索时按需暴露单个工具描述；**百级工具场景下工具描述 Token 占用减少 85%**，解决工具生态膨胀后上下文被挤占的问题

- **两层渐进式上下文压缩**：Agent 长任务跑几十轮后历史堆积逼近上下文窗口上限，设计两层防御：第一层超大工具结果即时落盘只留引用指针，第二层接近上限时调 LLM 生成压缩摘要替换历史，压缩边界严格对齐 Function Calling 协议的 tool_use / tool_result 配对约束以避免 API 拒绝；**单次会话可持续数小时不丢失关键上下文**，Token 成本较无压缩方案显著降低

- **五层安全隔离**：Agent 全自动模式下模型频繁触发权限弹窗严重打断工作流，设计五层权限确认机制，结合规则白名单、工具类型过滤与 allow-always 规则记忆自动放行重复性操作；**单次会话权限弹窗从平均 30 次降至 5 次以内**，全自动模式下未出现过越权操作事故；可选启用 OS 级沙箱（macOS seatbelt / Linux bwrap），内核级隔离进一步兜底

- **评测驱动优化**：缺乏量化评测使上下文压缩策略的优化方向不明确，搭建评测流水线接入 SWE-bench-Live 数据集，同时自主设计上下文信息保留测试：对话开头注入关键信息，连续多轮撑满上下文触发压缩，量化检测压缩后信息留存率；首次运行发现信息保留率仅 17%，定位到压缩摘要 prompt 缺陷，改写 prompt 后**信息保留率从 17% 提升至 100%，代码零改动**

- **多 Agent 并行协作**：大型重构任务单 Agent 串行处理耗时过长，设计 Lead-Teammate 多 Agent 架构，Lead 分解任务后并行派发，各 Teammate 在独立 git worktree 内执行，文件级隔离避免并发写冲突，通过 FileMailBox 异步消息通道汇报进度；**大型重构任务处理效率提升约 60%**

- **Java 工程化专项**：通用 Coding Agent 处理 Java 项目时依赖原始 Bash，无法结构化解析编译错误，Agent 频繁无效重试；新增 JavaBuild / JUnitRun / JavaDependency 三个专项工具，自动探测构建系统（pom.xml → Maven / build.gradle → Gradle），结构化提取编译错误，支持 class / method 级 JUnit 定向执行（Maven `-Dtest` / Gradle `--tests`）；**首轮编译修复成功率提升，定向测试执行时间缩短至全量运行的 1/N**

- **可观测性体系**：Agent 策略调优缺乏数据支撑，无法定位性能瓶颈；在工具执行出口和 Loop 完成点埋点，采集工具调用成功率、执行延迟、每轮 Token 消耗与错误原因分类，支持 Session 级 Trace 追踪；`/metrics` 命令实时查看，**为上下文压缩策略和工具超时参数的量化调优提供数据依据**

---

## 配套学习路线

| 顺序 | 亮点 | 核心代码 | 说明 |
|---|---|---|---|
| 1 | Agent Loop 主循环（地基，先学） | [Agent.java `agentLoop()`](../src/main/java/com/codepal/agent/Agent.java) | 所有亮点的底层，必须先吃透 |
| 2 | 工具并发安全分批 | [StreamingExecutor.java](../src/main/java/com/codepal/agent/StreamingExecutor.java)、[ToolCategory.java](../src/main/java/com/codepal/tool/ToolCategory.java) | 第 1 周 |
| 3 | 两层上下文压缩 | [ContextCompactor.java](../src/main/java/com/codepal/compact/ContextCompactor.java) | 第 2 周，最有含量 |
| 4 | MCP 延迟加载 | [McpManager.java](../src/main/java/com/codepal/mcp/McpManager.java)、[ToolSearchTool.java](../src/main/java/com/codepal/tool/impl/ToolSearchTool.java) | 第 2 周 |
| 5 | 五层安全隔离 | [PermissionChecker.java](../src/main/java/com/codepal/permission/PermissionChecker.java)、[SeatbeltSandbox.java](../src/main/java/com/codepal/sandbox/SeatbeltSandbox.java) | 第 3 周 |
| 6 | Java 工程化专项 | [JavaBuildTool.java](../src/main/java/com/codepal/tool/impl/JavaBuildTool.java)、[JUnitRunTool.java](../src/main/java/com/codepal/tool/impl/JUnitRunTool.java) | 第 3 周，你新写的，先学 |
| 7 | 可观测性体系 | [MetricsCollector.java](../src/main/java/com/codepal/metrics/MetricsCollector.java)、[TraceStore.java](../src/main/java/com/codepal/metrics/TraceStore.java) | 第 3 周，你新写的，先学 |
| 8 | 评测框架 | [EvalCase.java](../src/main/java/com/codepal/eval/EvalCase.java)、[EvalRunner.java](../src/main/java/com/codepal/eval/EvalRunner.java) | 第 4 周 |
| 9 | 多 Agent 协作 | [TeamManager.java](../src/main/java/com/codepal/teams/TeamManager.java)、[TeammateRunner.java](../src/main/java/com/codepal/teams/TeammateRunner.java) | 第 4 周 |
