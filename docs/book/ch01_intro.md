# 第1章：初识 Coding Agent

> 目标：搞清楚"Coding Agent 是什么"、"它和普通 ChatGPT 有什么本质区别"、"CodePal 这个项目是干什么的"。

---

## 1.1 从一个问题出发

你有没有用过 ChatGPT 帮你写代码？体验大概是这样的：

1. 你描述需求
2. ChatGPT 给你一段代码
3. 你复制粘贴，跑一下，报错了
4. 你把报错贴回去，ChatGPT 再给你一版
5. 反复几轮……

这个过程里，**你是"手"**，ChatGPT 是"脑"。脑想好了，还得你亲自动手去执行、去验证、去反馈。

**Coding Agent 想做的事**：让 AI 自己既当脑，又当手。

---

## 1.2 什么是 Agent？

Agent（智能体）这个词来自人工智能领域，核心特征是：

- **感知环境**（Perception）：能接收外部信息
- **做出决策**（Decision）：根据信息判断下一步
- **执行行动**（Action）：真正改变环境
- **观察结果**（Observation）：看行动结果，决定下一步

这四步循环不止。

**Coding Agent 的具体映射：**

| Agent 概念 | CodePal 的实现 |
|-----------|--------------|
| 感知环境 | 读取文件、搜索代码、执行命令 |
| 做出决策 | LLM 分析信息，决定下一步调用哪个工具 |
| 执行行动 | 调用工具：写文件、修改代码、运行测试 |
| 观察结果 | 工具结果返回给 LLM，成为下一轮输入 |

---

## 1.3 Coding Agent vs 普通 Chat：本质区别

| 对比维度 | 普通 Chat（ChatGPT） | Coding Agent（CodePal） |
|---------|--------------------|-----------------------|
| 交互模式 | 一问一答，每轮独立 | 多轮自主循环，连续行动 |
| 工具能力 | 只输出文字 | 可读写文件、执行命令、搜索代码 |
| 上下文 | 单次会话，几轮后就忘 | 长任务多轮，主动管理上下文 |
| 主动性 | 被动等待用户输入 | 主动规划步骤，自行执行 |
| 错误处理 | 靠用户反馈 | 自动捕获报错，自动重试修复 |

**类比**：普通 Chat 是顾问，你提问他回答。Coding Agent 是承包商，你说需求，他自己动手干完给你。

---

## 1.4 ReAct 框架：Agent 的理论基础

2022 年 Google 发表了论文《ReAct: Synergizing Reasoning and Acting in Language Models》，提出了 Agent 的经典范式：

```
Thought（思考）→ Action（行动）→ Observation（观察）→ Thought → ...
```

每一步：
- **Thought**：LLM 分析当前情况，决定下一步做什么
- **Action**：调用一个工具
- **Observation**：工具返回结果，作为下一次 Thought 的输入

这个循环就叫 **ReAct Loop**，也叫 **Agent Loop**。

CodePal 的 `Agent.java` 就是这个框架的工程实现。

**为什么 Reasoning + Acting 结合比纯 Acting 好？**（面试常追问）

你可能会想：既然目标是"调工具完成任务"，为什么不让模型直接输出 Action（调工具）就好，还要中间的 Thought（思考）？

因为**纯 Action 会累积错误**。如果模型每一步都直接跳到"调哪个工具"，没有先想一想"我现在处于什么状态、上一步的结果说明了什么、接下来该干嘛"，它很容易被上一个工具的输出带偏，一步错步步错。加入 Thought 这一步，相当于给模型一个"自我纠偏"的机会——它能在行动前梳理逻辑，发现"咦上一步的报错说明我方向错了"，从而及时调整。实验证明，ReAct 在复杂任务上的成功率显著高于纯 Action。

**ReAct 和 CoT（Chain-of-Thought）什么关系？**（新手极易混淆）

- **CoT（思维链）**：让模型"把推理过程一步步写出来"再给答案，纯粹在**脑内**推理，不与外部交互。适合数学题、逻辑题这类"想清楚就能答"的任务。
- **ReAct**：CoT 的推理 + 真实世界的行动交替进行。它不只是"想"，还会"动手"（调工具），拿到真实反馈（Observation）再继续想。

一句话：**CoT 是"只思考"，ReAct 是"边思考边行动、用行动结果修正思考"**。ReAct 可以看作把 CoT 从封闭的脑内推理，扩展到了能与环境交互的开放循环。Coding Agent 必须用 ReAct——因为写代码这件事，不跑一下永远不知道对不对。

**ReAct 的局限与演进**（能说出来是加分项）

ReAct 是"走一步看一步"，没有全局规划，遇到需要长远规划的任务容易迷失方向。后来出现了改进范式：
- **Plan-and-Execute**：先让模型制定完整计划，再逐步执行，减少"走偏"。
- **Reflexion**：在 ReAct 基础上加"反思"——失败后总结教训，下次避免重蹈覆辙。

CodePal 以 ReAct 为主干（配合第4章的目标锚定和自愈机制来缓解"走偏"），面试时可以说"我们用 ReAct 打底，用 Harness 层的工程手段弥补它没有全局规划的短板"。

---

## 1.5 CodePal 是什么

CodePal 是一个用 **Java 21** 写的 Coding Agent 框架，参考 Claude Code（Anthropic 官方的 Coding Agent）的架构设计。

**定位**：在终端里运行，接受用户自然语言指令，通过调用工具自动完成编程任务。

**三种运行模式**（见 `CodePal.java`）：

```java
// TUI 模式（默认）：终端交互界面
var model = new CodePalModel(...);
var program = new Program(model);
program.run();

// Print 模式（-p "prompt"）：非交互，输出到 stdout，适合脚本调用
PrintMode.run(config, printPrompt, fmt);

// Remote 模式（--remote）：启动 HTTP + WebSocket 服务器，支持 Web 界面
var server = new RemoteServer(...);
server.run();
```

---

## 1.6 项目整体架构图

```
用户输入（TUI / HTTP / CLI）
         │
         ▼
   CodePalModel（TUI 控制器）
         │
         ▼
      Agent.java ← 核心：AgentLoop
     ╱    │    ╲
    ╱     │     ╲
LlmClient │    ToolRegistry
（对接     │    （工具管理）
Anthropic  │
/OpenAI）  │
           │
    ConversationManager
    （对话历史管理）
           │
    ContextCompactor
    （上下文压缩）
```

**主要模块一览：**

| 包名 | 职责 |
|-----|-----|
| `agent/` | Agent Loop 主循环、流式工具执行器 |
| `llm/` | LLM 客户端（Anthropic / OpenAI） |
| `tool/` | 工具基类、注册表、具体工具实现 |
| `conversation/` | 对话历史数据结构 |
| `compact/` | 两层上下文压缩 |
| `permission/` | 权限安全检查 |
| `mcp/` | MCP 协议工具扩展 |
| `memory/` | 长期记忆系统 |
| `teams/` | 多 Agent 协作 |
| `tui/` | 终端 UI（Tea 架构） |

---

## 1.7 运行起来看看

```bash
# 构建
./gradlew shadowJar

# 运行（先配置好 .codepal/config.yaml，填入 API Key）
java -jar build/libs/codepal.jar

# 非交互模式测试
java -jar build/libs/codepal.jar -p "帮我列出当前目录下的 Java 文件"
```

配置文件示例（`.codepal/config.yaml.example`）：
```yaml
providers:
  - name: claude
    protocol: anthropic
    model: claude-opus-4-8
    api_key: sk-ant-...
```

---

## 1.8 面试官可能问的问题

**Q：什么是 Coding Agent？和普通 LLM 应用有什么区别？**

> Coding Agent 是在 LLM 外围套了一个 Harness（外壳）的自主执行系统。普通 LLM 应用是"问答式"，Agent 是"循环执行式"：LLM 每次决定调用哪个工具，工具结果反馈给 LLM，LLM 再决定下一步，直到任务完成。本质区别是：Agent 能自主感知环境（读文件）、执行行动（写代码、运行命令）、观察结果，形成闭环，而不依赖人工介入每一步。

**Q：你了解 ReAct 框架吗？**

> ReAct 是 Google 2022 年提出的 Agent 范式，核心是 Thought-Action-Observation 循环交替。我们的 CodePal 就是这个框架的工程实现：Thought 对应 LLM 分析和决策，Action 对应工具调用，Observation 对应工具结果注入历史。Java 21 上用 Virtual Thread + Streaming + BlockingQueue 把这套循环做成了生产可用的系统。

**Q：Coding Agent 有哪些典型的技术挑战？**

> 三类核心挑战：
> 1. **上下文管理**：长任务会话历史堆积，逼近上下文窗口上限，需要主动压缩
> 2. **可靠性**：工具调用失败、API rate limit、context 超长，Agent 必须能自愈
> 3. **安全性**：Agent 有写文件、执行命令的能力，必须有权限控制，防止越权操作

**Q：Agent、Workflow、Chain 三者有什么区别？**（Anthropic《Building Effective Agents》核心考点）

> 关键区别在**谁决定下一步**：
> - **Chain（链）**：步骤是**代码写死**的，A→B→C 固定流程，模型只填空。可靠但不灵活。
> - **Workflow（工作流）**：有分支/编排，但路径仍由**代码预定义**（if 结果满足条件走这条，否则走那条）。模型在预设的框架里工作。
> - **Agent（智能体）**：**下一步由模型自己决定**——模型看当前状态，自主选择调哪个工具、要不要继续。灵活但需要 Harness 兜底可靠性。
>
> 判断标准：如果任务路径能提前枚举清楚，用 Workflow（更可控、更省钱）；如果路径无法预知、需要模型临场判断，才用 Agent。**不是所有场景都该上 Agent**——能用确定性 Workflow 解决的，用 Agent 反而是过度设计（更贵、更不可控）。

**Q：什么任务不适合用 Agent？**（考察工程判断力）

> 三类：①路径确定、可枚举的任务（用 Workflow 更好，如固定的数据 ETL）；②对延迟/成本极敏感的高频场景（Agent 多轮 LLM 调用又慢又贵）；③错误代价极高且难回滚的操作（Agent 有不确定性，金融交易、生产数据库直接写这类要人工把关）。选 Agent 的前提是"任务需要自主决策"且"错误可控可恢复"。

---

## 1.9 生产中可能遇到的问题

**问题1：Agent 进入死循环，不知道什么时候停**

根本原因：模型一直认为任务没完成，不断调用工具。
解法：`maxIterations` 上限保护（CodePal 里可配置）；同时设计好 system prompt，让模型清楚何时该结束。

**问题2：第一次运行就因 API Key 报错挂掉，不友好**

CodePal 在 `AnthropicClient` 构造器里做了早期检查：
```java
if (apiKey.isEmpty()) {
    throw new LlmException.AuthenticationException("Anthropic API key not found...");
}
```
启动时立刻报清楚的错误，而不是等到第一次 API 调用才报错。生产系统一定要做这类"快速失败"设计。

**问题3：模型选择影响效果巨大**

不同任务适合不同模型。复杂多文件重构用 Opus（能力强），简单问答用 Haiku（速度快、成本低）。CodePal 通过 `ModelResolver` 支持模型别名，让用户不用关心具体模型版本号。

**问题4（用户视角）：用户不知道 Agent 什么时候"跑完了"**

普通 Chat 是一问一答，答完就完了。Agent 会自主跑很多轮（读文件、改代码、跑测试……），用户盯着终端看一堆工具调用刷过，不确定 Agent 是还在干活、还是卡住了、还是已经做完在等我。

解法：明确的完成信号（LoopComplete 时给一句"任务完成"总结）、执行中的进度提示（"正在读取 3 个文件…"）、以及区分"我在思考""我在执行工具""我做完了"三种状态的 UI 呈现。让用户始终知道 Agent 处于什么状态，是 Agent 产品体验的第一课。

**问题5（用户视角）：任务跑到一半，用户想改需求或喊停**

用户看着 Agent 往错误方向跑（比如它误解了需求，正在改一堆不该改的文件），想立刻打断说"停，我不是这个意思"。如果框架不支持中途打断，用户只能眼睁睁看它跑完或强杀进程。

解法：支持 Ctrl+C 优雅中断（停在当前轮边界，保留已完成的工作），以及"排队插话"——用户在 Agent 执行时输入的内容，在下一轮开始时作为新指令注入。可打断性是自主 Agent 必须有的安全阀。

**问题6（用户视角）：用户怕 Agent"乱来"，不敢放手让它执行**

Agent 能改文件、跑命令，新用户第一反应是"它会不会把我代码搞坏、会不会 rm 掉重要文件"。这种不信任会让用户全程盯着、每步都要确认，Agent 的自主性价值大打折扣。

解法：这正是第6章权限系统要解决的——危险操作弹窗确认、Plan Mode（只读规划不动手）、可选沙箱。让用户对"Agent 能做什么、不能做什么"有清晰、可控的预期，信任才能建立。

---

*下一章：让 AI 开口说话 — LLM 客户端与流式调用的实现*
