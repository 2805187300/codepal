# 第13章：SubAgent — 子 Agent 的调度机制

> 目标：理解 Agent 工具（`Agent` tool）是如何把一个工具调用变成一个完整独立 Agent 的；三种执行路径（fork、sync、async）的区别；worktree 隔离的触发时机。
> 关键文件：`subagent/AgentTool.java`、`subagent/SubAgentSpec.java`、`subagent/ToolFilter.java`

---

## 13.1 为什么需要 SubAgent？

一个复杂任务中，主 Agent 可能面临这样的场景：

> "帮我分析这个 Java 项目的架构，同时写一份改进建议报告。"

这个任务可以拆成两个相对独立的子任务：
1. 探索代码库、理解架构（只读工具就够了）
2. 根据探索结果撰写报告

如果都在主 Agent 里串行跑，上下文会很快积累。更重要的是，有些子任务适合用不同的**工具集约束**（分析只需读工具）或者**不同的模型**（简单任务用 Haiku 省成本）。

**SubAgent 的核心价值：**
- 任务隔离：子 Agent 有独立的 `ConversationManager`，不污染主对话
- 工具约束：根据子任务类型过滤可用工具
- 模型灵活：可以给子 Agent 指定不同的 LLM 模型
- 并行执行：`run_in_background: true` 后台异步跑，主 Agent 不阻塞

---

## 13.2 Agent Tool 的 schema：LLM 怎么调用它

```java
// subagent/AgentTool.java — schema 定义

Map<String, Object> properties = new LinkedHashMap<>();
properties.put("description",    ...);  // 必填：3-5词描述
properties.put("prompt",         ...);  // 必填：给子 Agent 的任务说明
properties.put("subagent_type",  ...);  // 可选：agent 类型（general-purpose/plan/explore/...）
properties.put("model",          ...);  // 可选：模型覆盖（sonnet/opus/haiku）
properties.put("run_in_background", ...); // 可选：是否后台异步
properties.put("isolation",      ...);  // 可选："worktree" 文件级隔离
properties.put("team_name",      ...);  // 可选：加入团队（第15章）
```

LLM 调用示例：
```json
{
  "tool": "Agent",
  "input": {
    "description": "分析 Agent.java 架构",
    "prompt": "请仔细阅读 src/main/java/com/codepal/agent/Agent.java，分析 agentLoop 的完整流程并总结关键设计决策。",
    "subagent_type": "explore",
    "model": "haiku"
  }
}
```

---

## 13.3 三条执行路径

`AgentTool.execute()` 里有一个清晰的路由逻辑：

```java
@Override
public ToolResult execute(Map<String, Object> args) {
    String subagentType = getStringArg(args, "subagent_type");
    String teamName = getStringArg(args, "team_name");
    boolean runInBackground = Boolean.TRUE.equals(args.get("run_in_background"));

    // 路由 1：团队成员（第15章专讲）
    if (teamName != null && !teamName.isEmpty()) {
        return runAsTeammate(...);
    }

    // 路由 2：fork（没有 subagent_type）
    if (subagentType == null || subagentType.isEmpty()) {
        return runFork(description, prompt, modelOverride);
    }

    // 路由 3：同步 or 异步
    if (runInBackground) {
        return runAsync(spec, description, prompt, modelOverride);
    }
    return runSync(spec, description, prompt, modelOverride, isolation);
}
```

**路径一：Fork（无 subagent_type）**

Fork 是最轻量的子 Agent，适合"帮我做这件事，但不要打扰我的上下文"。它继承父 Agent 的完整工具集，携带父对话历史（作为背景），在后台独立运行。

```java
private ToolResult runFork(String description, String prompt, String modelOverride) {
    // 防止嵌套 fork（子 Agent 里不能再 fork）
    if (FORK_QUERY_SOURCE.equals(querySource)) {
        return ToolResult.error("cannot fork from a forked agent");
    }

    // 复制父对话历史 + 注入 fork 专属 boilerplate
    ConversationManager forkedConv = buildForkedConversation(parentConversation, prompt);

    // 继承父工具集（含 AgentTool，但 querySource 被标记为 fork）
    ToolRegistry forkedRegistry = ToolFilter.cloneForFork(parentRegistry);

    // 后台异步执行
    String taskId = taskManager.spawnForkAgent(...);
    return ToolResult.success("Forked agent launched in background (task " + taskId + ")");
}
```

**Fork Boilerplate**（注入给子 Agent 的行为约束）：

```java
private static final String FORK_BOILERPLATE = """
    You are a forked worker process. You are NOT the main agent.
    Rules (non-negotiable):
    1. Do NOT fork again.
    2. Do NOT converse, ask questions, or request confirmation.
    3. Use tools directly: read files, search code, make changes.
    4. Stay strictly within your assigned task scope.
    5. Final report must be under 500 characters, starting with "Scope:".
    """;
```

这 5 条规则解决了"子 Agent 行为不受控"的问题：
- 规则 1：防止无限递归 fork
- 规则 2-3：fork Agent 不是对话 Agent，要直接做事
- 规则 4：防止任务蔓延（scope creep）
- 规则 5：限制输出大小，避免子 Agent 输出占满上下文

**孤立 tool_use 的修复**：

```java
// buildForkedConversation：复制父对话历史时处理"待执行的 tool_use"
for (var msg : parent.getMessages()) {
    if (msg.getToolUses() != null && msg.getToolResults() == null) {
        // 父 Agent 有 tool_use 但还没执行，fork 时用占位符填充 tool_result
        forked.addAssistantFull(msg.getContent(), msg.getThinkingBlocks(), msg.getToolUses());
        var placeholders = msg.getToolUses().stream()
                .map(tu -> new ToolResultBlock(tu.toolUseId(),
                        "(tool execution interrupted by fork)", false))
                .toList();
        forked.addToolResultsMessage(placeholders);
    }
    // ...
}
```

这个细节很重要：fork 发生时，父 Agent 可能刚刚收到 LLM 的 tool_use 还没执行。直接复制这条历史到子 Agent，API 会报 400（tool_use 没有对应的 tool_result）。这里用占位符"补齐"配对，保证子 Agent 的历史合法。

---

**路径二：runSync（同步执行）**

指定了 `subagent_type` 且非后台，主 Agent 阻塞等待子 Agent 完成：

```java
private ToolResult runSync(SubAgentSpec spec, String description, String prompt,
                           String modelOverride, String isolation) {
    // 1. 按 spec 过滤工具（explore 只有读工具，plan 只有读工具 + ExitPlanMode）
    ToolRegistry subRegistry = ToolFilter.filterForAgent(parentRegistry, spec);

    // 2. 选择 LLM 客户端（可能是不同模型）
    LlmClient subClient = selectClient(spec.model(), modelOverride);

    // 3. 创建子 Agent
    Agent subAgent = new Agent(subClient, subRegistry, protocol, providerConfig);
    subAgent.setMaxIterations(spec.maxTurns() > 0 ? spec.maxTurns() : 200);

    // 4. 可选：创建 git worktree 隔离
    if ("worktree".equals(isolation) && worktreeManager != null) {
        AgentWorktree.Result wtResult = AgentWorktree.create(slug, ...);
        subAgent.setWorkDir(wtResult.worktreePath());
        prompt = AgentWorktree.buildNotice(...) + "\n\n" + prompt;
    }

    // 5. 启动并阻塞消费事件
    BlockingQueue<AgentEvent> queue = subAgent.run(conv);
    while (true) {
        AgentEvent event = queue.poll(60, TimeUnit.SECONDS);
        switch (event) {
            case AgentEvent.StreamText st  -> output.append(st.text());
            case AgentEvent.LoopComplete   -> { return ToolResult.success(output.toString()); }
            case AgentEvent.ErrorEvent err -> { return ToolResult.error(err.message()); }
            // ...
        }
    }

    // 6. worktree 清理（有改动则保留，否则删除）
    if (WorktreeChanges.hasChanges(wtResult.worktreePath(), wtResult.headCommit())) {
        wtInfo = "Worktree kept at " + wtResult.worktreePath() + "...";
    } else {
        AgentWorktree.remove(wtResult.worktreePath(), ...);
    }
}
```

**worktree 隔离的"有改动则保留"逻辑**：

子 Agent 在 worktree 里工作。任务完成后，检查 worktree 里是否有未提交的改动或新 commit。如果有（子 Agent 写了代码），保留 worktree 让主 Agent 或用户决定如何合并。如果没有（子 Agent 只是读了文件分析了一下），自动删除 worktree，不留垃圾。

---

**路径三：runAsync（后台异步）**

```java
private ToolResult runAsync(SubAgentSpec spec, String description, String prompt, String modelOverride) {
    LlmClient subClient = selectClient(spec.model(), modelOverride);

    // taskManager 负责在后台线程里跑 Agent，完成后往通知队列发消息
    String taskId = taskManager.spawnSubAgent(subClient, parentRegistry, protocol, providerConfig, spec, prompt);

    return ToolResult.success(
        "Agent \"%s\" launched in background (task %s). You will be notified when it completes."
                .formatted(description, taskId));
}
```

主 Agent 收到"已在后台启动"的 tool_result，继续做其他事情。子 Agent 完成后，`notificationFn` 把完成通知注入主 Agent 下一轮的 system reminder，主 Agent 就能知道结果了（对应 `agentLoop` 里每轮开始时的 `notificationFn.get()`）。

---

## 13.4 ToolFilter：工具集过滤

不同类型的子 Agent 有不同的工具权限：

```java
// subagent/ToolFilter.java（概念）
public class ToolFilter {

    // 按 spec 的 allowedTools 白名单过滤
    public static ToolRegistry filterForAgent(ToolRegistry parent, SubAgentSpec spec) {
        ToolRegistry sub = new ToolRegistry();
        for (Tool tool : parent.listTools()) {
            if (spec.allowedTools().contains(tool.name())) {
                sub.register(tool);
            }
        }
        return sub;
    }

    // fork 继承父工具集，但 AgentTool 的 querySource 被标记
    public static ToolRegistry cloneForFork(ToolRegistry parent) {
        ToolRegistry forked = new ToolRegistry();
        for (Tool tool : parent.listTools()) {
            if (tool instanceof AgentTool at) {
                // clone AgentTool 并标记为 fork，阻止嵌套 fork
                forked.register(at.cloneWithQuerySource(FORK_QUERY_SOURCE));
            } else {
                forked.register(tool);
            }
        }
        return forked;
    }
}
```

**内置 Agent 类型的工具白名单**：

| 类型 | 可用工具 |
|------|---------|
| `general-purpose` | 全部工具 |
| `explore` | ReadFile, Glob, Grep（只读） |
| `plan` | ReadFile, Glob, Grep, Agent, ExitPlanMode（只读 + 规划） |

---

## 13.5 面试官可能问的问题

**Q：你们的 SubAgent 机制是怎么设计的？三种执行路径有什么区别？**

> Agent Tool 是一个普通工具，但执行时会创建独立的子 Agent 实例。三种路径：Fork 继承父对话历史在后台异步跑，适合"帮我做这件事"；Sync 按 spec 过滤工具同步阻塞等待结果，适合有类型约束的子任务；Async 后台异步，主 Agent 继续工作，子 Agent 完成后通过通知队列通知主 Agent。Worktree 隔离可以叠加在 Sync 上，让子 Agent 在独立的 git 工作树里操作文件，做完有改动就保留，没改动就自动清理。

---

*下一章：Worktree — git 工作树的文件级隔离*
