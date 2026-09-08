# CodePal 完整流程走查：真实开发场景全解析

---

## 场景设定

> **任务**：你是一名后端工程师，接到需求：把公司 Java 项目的日志框架从 Log4j 2 迁移到 SLF4J + Logback。项目有 47 个 Java 文件，分散在 8 个包里，历史上有一些奇怪的自定义 Log4j Appender，CI 跑的是 JUnit 5 + Gradle。
>
> 你打开终端，输入：`java -jar codepal.jar`

---

## 第一阶段：启动与初始化

### 1.1 程序入口

`CodePal.java` 的 `main()` 解析命令行参数，加载 `.codepal/config.yaml`（`ConfigLoader`），决定以哪种模式启动：

- 有 `-p` 参数 → `PrintMode`（非交互式，适合脚本调用）
- 有 `--remote` 参数 → `RemoteServer`（HTTP + WebSocket，供 Web 前端访问）
- 默认 → `TUI`（终端交互模式，今天的场景）

### 1.2 TUI 初始化

`Program`（Tea 框架主循环）启动，`CodePalModel` 作为 UI 状态机初始化，状态进入 `PROVIDER_SELECT`，显示 Provider 选择界面（Anthropic / OpenAI / 兼容 API）。

你选择 Anthropic，状态跳转到 `READY`。

### 1.3 各子系统并发初始化

在 `READY` 状态完成之前，后台并发初始化：

```
MCP 连接：McpManager.connectAll()
  → 读取 config.yaml 里的 mcp_servers 配置
  → 对每个 MCP server 建立 stdio/HTTP transport
  → 失败的 server 记进 errors 列表，不阻断启动
  → 成功的工具以 McpToolWrapper 注册到 ToolRegistry（shouldDefer=true）

Skill 加载：SkillCatalog 三层扫描
  → builtin（内置 Skill）
  → ~/.codepal/skills/（用户全局）
  → .codepal/skills/（项目级）
  → Phase-1 只读 frontmatter（快速），body 按需热加载

记忆加载：MemoryManager
  → 读 ~/.codepal/memory/MEMORY.md（用户级索引）
  → 读 .codepal/memory/MEMORY.md（项目级索引）
  → 拼装 system reminder 文本，待注入 System Prompt

Hook 初始化：HookEngine
  → 读 .codepal/hooks.yaml
  → 注册 SESSION_START 事件回调
  → 触发 SESSION_START hook（比如发一条 Slack 通知"Agent 会话开始"）

沙箱检测：SandboxFactory
  → macOS: 检测 /usr/bin/sandbox-exec 是否存在
  → Linux: 检测 bwrap 是否可用
  → 可用则后续 Bash 命令包一层 OS 沙箱
```

### 1.4 System Prompt 构建

`PromptBuilder` 按优先级拼装各 Section：

```
Priority 10: 核心身份（"你是 CodePal，一个 CLI Agent..."）
Priority 20: 环境上下文（OS=macOS, Shell=zsh, Branch=main, Date=2026-08-26）
Priority 30: 工具使用规范（并行调用、工具分类说明）
Priority 40: CLAUDE.md 内容（你的项目 CLAUDE.md 说明构建方式、代码规范）
Priority 50: Tone & Style（简洁、不加注释、一句话总结）
Priority 60: 输出效率（先说做什么再调工具）
Priority 70: 记忆索引（MEMORY.md 两层索引内容）
Priority 80: Skill SOP（当前激活的 Skill 操作规程，如有）
```

---

## 第二阶段：第一轮对话——任务理解与规划

### 2.1 你发送任务

```
你：帮我把项目的 Log4j 2 迁移到 SLF4J + Logback，
    先分析一下涉及哪些文件，然后制定迁移计划
```

`CodePalModel` 状态切换到 `STREAMING`，调用 `Agent.run(conv)`，在虚拟线程里启动 Agent Loop。

### 2.2 Agent Loop 第一轮

**Step 1：准备工具列表**

```java
var iterToolSchemas = registry.getAllSchemas(protocol);
```

`ToolRegistry.getAllSchemas()` 遍历所有注册工具：
- `shouldDefer=false` 的核心工具全部注入（ReadFile、WriteFile、EditFile、Bash、Glob、Grep、ToolSearch、Agent、JavaBuild、JUnitRun...）
- `shouldDefer=true` 的 MCP 工具跳过（只有名字列表以 system reminder 形式注入）

**Step 2：注入 deferred 工具名提示**

```
system reminder:
"The following deferred tools are available via ToolSearch:
mcp__github__search_repos, mcp__jira__create_issue, ..."
```

**Step 3：Layer 1 工具结果预算检查**

`ToolResultBudget.apply()` 扫描对话历史——第一轮历史为空，跳过。

**Step 4：Layer 2 上下文压缩检查**

`ContextCompactor.manage()` 估算 Token 数——才几百 Token，远未到软触发线（effectiveWindow - 13,000），跳过。

**Step 5：发送 LLM 请求**

`client.stream(conv, tools)` 把 System Prompt + 用户消息 + 工具 schema 列表发给 Anthropic API，开始消费流式事件。

LLM 决定先探索项目结构，输出一系列 tool_use：

```json
[
  {"name": "Glob", "input": {"pattern": "src/**/*.java"}},
  {"name": "Bash", "input": {"command": "grep -rn 'log4j\\|Logger\\|LogManager' src/ --include='*.java' -l"}}
]
```

TUI 实时渲染工具调用图标和描述。

### 2.3 StreamingExecutor 并发执行

`StreamingExecutor.executeAll()` 接收到两个工具调用：

```java
// partitionToolCalls() 分析：
// Glob → READ 类，shouldDefer=false
// Bash → COMMAND 类
// 两个独立工具，但 Bash 是 COMMAND 类，不能和 READ 并行（语义安全原则）
// → 实际：Glob 先执行，Bash 后执行
```

**Glob 执行前**：`PermissionChecker.check("Glob", "src/**/*.java")`
- Layer 1：Glob 不在 SAFE_COMMANDS 白名单（不是 shell 命令）
- Layer 5：READ 类 → `PermissionMode.DEFAULT` → `ALLOW`
- 直接放行，无弹窗

**Bash 执行前**：`PermissionChecker.check("Bash", "grep -rn ...")`
- Layer 1：`grep` 开头且无管道/分号 → `isSafeCommand()` 返回 true → `ALLOW`
- 直接放行，无弹窗

**沙箱包装**（如果启用）：`SeatbeltSandbox.wrap("grep -rn ...")`
- 生成 seatbelt profile：deny default → allow read → allow write 仅项目目录
- 实际执行：`/usr/bin/sandbox-exec -f /tmp/profile.sb grep -rn ...`

**Hook 触发**：`HookEngine.fire(PRE_TOOL_USE, {toolName: "Bash", command: "grep..."})`
- 如果你配置了 `pre_tool_use` hook，这里触发（比如记录审计日志）

两个工具结果返回，追加到对话历史。

### 2.4 LLM 分析结果，进入 Plan 模式

LLM 看到 47 个 Java 文件，grep 到 23 个含有 Log4j 引用。LLM 决定使用 Plan 模式制定详细计划：

```json
{"name": "ExitPlanMode", "input": {"plan": "迁移计划..."}}
```

等等——实际上是 LLM 先切换到 PLAN 模式（通过用户或配置），Agent Loop 检测到：

```java
if (checker != null && checker.getMode() == PermissionMode.PLAN) {
    String planPath = PlanFile.getOrCreatePlanPath(wd);
    String reminder = PlanModePrompt.buildReminder(planPath, planExists, iteration);
    conv.addSystemReminder(reminder);
}
```

在 Plan 模式下，只有白名单工具可用（`PLAN_MODE_ALLOWED_TOOLS`），LLM 只能读文件和思考，不能写文件（除了 `.codepal/plans/` 目录）。LLM 把计划写进 `.codepal/plans/migration-plan.md`，然后调用 `ExitPlanMode`，等待你审批。

**TUI 弹出 Plan 审批对话框**（`PlanApprovalDialog`）：

```
┌─────────────────────────────────────┐
│ Agent 已制定计划，是否批准执行？      │
│                                     │
│ 迁移计划：                          │
│ 1. 更新 build.gradle 依赖            │
│ 2. 迁移 23 个文件的 import           │
│ 3. 替换自定义 Appender               │
│ 4. 运行测试验证                      │
│                                     │
│  [批准]  [拒绝]  [查看完整计划]      │
└─────────────────────────────────────┘
```

你点击批准，`PermissionMode` 切回 `DEFAULT`，Agent 继续。

---

## 第三阶段：多 Agent 并行执行迁移

### 3.1 Lead 决定启动团队

LLM 分析：23 个文件分布在 8 个包里，包间耦合低，可以并行。LLM 调用 `TeamCreate`：

```json
{"name": "TeamCreate", "input": {"team_name": "migration-team"}}
```

`TeamManager.createTeam("migration-team")` 初始化：
- 创建 `.codepal/teams/migration-team/inboxes/` 目录（FileMailBox 存放位置）
- 创建 `SharedTaskStore`（`tasks.json`）

### 3.2 为每个 Teammate 创建 git worktree

LLM 派发 3 个 Teammate，每个负责约 8 个文件：

```json
{
  "name": "Agent",
  "input": {
    "description": "migrate-service-layer",
    "prompt": "迁移 src/main/java/com/example/service/ 下所有文件...",
    "subagent_type": "general-purpose",
    "team_name": "migration-team",
    "isolation": "worktree"
  }
}
```

`AgentTool.runAsTeammate()` 执行：

```java
// 创建 git worktree
var wtResult = AgentWorktree.create(
    "agent-a3f7b12",           // 随机 slug
    worktreeManager.getProjectRoot(),
    worktreeManager.getSymlinkDirs()  // 共享 .gradle、node_modules 等重目录
);
// wtResult.worktreePath() = ".codepal/teams/migration-team/worktrees/agent-a3f7b12"
// wtResult.worktreeBranch() = "agent-a3f7b12"（独立 branch）
```

`PostCreationSetup` 在新 worktree 里：
- 复制 `.codepal/settings.json`（权限配置）
- 创建 hooks 软链接
- 为重依赖目录（`.gradle/`）创建符号链接，避免重复下载

**worktree 注意事项**：`SlugValidator` 校验 branch 名，防止路径穿越（`../` 等危险字符）。

### 3.3 Teammate 启动（TMUX 模式）

`SpawnDispatcher.spawnTeammate()` 检测后端（`TeamManager.detectBackend()`）：

```java
if (System.getenv("TMUX") != null) return TeamMode.TMUX;
```

你在 TMUX 里，所以 `TmuxBackend` 创建新窗格：

```bash
tmux new-window -t migration-team:1 -n "migrate-service-layer" \
  "java -jar codepal.jar --teammate migration-team migrate-service-layer"
```

3 个 TMUX 窗格并排出现，你可以实时看到每个 Teammate 在做什么。

### 3.4 Teammate 执行循环

每个 Teammate 进入 `TeammateRunner.runInProcessTeammate()`：

```java
// 1. 注入角色信息
member.conv.addSystemReminder(
    "You are a member of team 'migration-team'. Your name is 'migrate-service-layer'.\n" +
    "Other team members: migrate-dao-layer, migrate-controller-layer\n" +
    "When you finish, simply stop calling tools — idle notification sent automatically."
);

// 2. 注入待处理邮件（FileMailBox 里的消息）
injectPendingMessages(team, memberName, member.conv);

// 3. 注入初始任务
member.conv.addUserMessage("迁移 src/main/java/com/example/service/ ...");

// 4. 跑 Agent Loop（和主 Agent 完全一样的机制）
var agentQueue = member.agent.run(member.conv);
drainAgentEvents(agentQueue, ...);

// 5. 完成后通知 Lead
team.sendMessage(memberName, "lead",
    "[idle] migrate-service-layer: completed initial task");

// 6. 等待下一个任务（500ms 轮询）
while (!Thread.currentThread().isInterrupted()) {
    var result = waitForNextPromptOrShutdown(team, memberName);
    // 轮询 FileMailBox...
}
```

### 3.5 Teammate 内部工作：真实遇到的问题

**问题1：自定义 Appender 需要重构，不只是替换 import**

Teammate-1 读到 `CustomKafkaAppender.java`，发现它继承了 `log4j.core.appender.AbstractAppender`，这不是简单的 import 替换。Teammate 遇到不确定的情况，调用 `AskUserQuestion`：

```json
{
  "name": "AskUserQuestion",
  "input": {
    "question": "CustomKafkaAppender 继承了 Log4j 的 AbstractAppender，\n迁移到 Logback 需要重写为 ch.qos.logback.core.AppenderBase。\n这个 Appender 的逻辑是否需要完整保留，还是可以简化？",
    "options": ["完整保留所有逻辑", "只保留核心功能", "暂时跳过，用占位符"]
  }
}
```

**TUI 主界面弹出权限/问题对话框**（`AskUserDialog`），你选择"完整保留"，答案通过 `ToolResult` 返回给 Teammate，Teammate 继续。

**问题2：上下文积累过多，触发压缩**

Teammate-2 处理了 12 个文件，读了大量代码，上下文 Token 逼近软触发线。`ContextCompactor.manage()` 触发：

```
Layer 1：offloadAndSnip()
  → 扫描历史，ReadFile 返回的大文件内容超过 50,000 字符
  → 写到 .codepal/tool_results/call_xyz789（工具调用 ID 为文件名）
  → 历史里替换为 "[Result of 73421 chars saved to .codepal/tool_results/call_xyz789]"
  → 释放约 73,000 字符的 Token 占用

Layer 2：autoCompact() 触发（仍超软触发线）
  → computeKeepStartIndex()：从最新消息往前数，保留 10,000 Token 或 5 条
  → 检查配对约束：保留边界不能落在孤立的 tool_result 上，自动后退
  → 调用 LLM 生成摘要（Chain-of-Thought: <analysis> 先思考，<summary> 输出摘要）
  → 摘要包含：已迁移文件列表、遇到的特殊情况、当前状态
  → 用"摘要 + 近期消息"替换整个历史
  → SessionManager.saveCompactBoundary() 写入 .jsonl，compact_boundary 记录
  → conv.clearUsageAnchor() 重置 Token 估算锚点
```

TUI 显示 `CompactEvent`（"Compacting context..."），用户看到进度提示。

**问题3：Bash 命令触发权限弹窗**

Teammate-3 需要执行 `./gradlew dependencies --configuration runtimeClasspath`，这个命令不在白名单：

```
PermissionChecker.check("Bash", "./gradlew dependencies..."):
  Layer 1: ./gradlew 不在 SAFE_COMMANDS → 不放行
  Layer 2: 无危险正则匹配
  Layer 3: 不涉及路径操作
  Layer 4: YAML 规则无匹配
  Layer 5: COMMAND 类 → PermissionMode.DEFAULT → ASK
```

TUI 弹出权限对话框：

```
┌──────────────────────────────────────────────┐
│ 允许执行命令？                                │
│                                              │
│ ./gradlew dependencies --configuration ...   │
│                                              │
│  [允许]  [允许（本次会话不再询问）]  [拒绝]   │
└──────────────────────────────────────────────┘
```

你点"允许（本次会话不再询问）"，`allowAlwaysRules.add("Bash:./gradlew dependencies...")`，后续相同命令自动放行。

**问题4：Gradle 构建失败，Java 专项工具介入**

Teammate-1 完成迁移后调用 `JavaBuild`：

```json
{"name": "JavaBuild", "input": {"task": "compileJava"}}
```

`JavaBuildTool` 执行 `./gradlew compileJava`，返回编译错误：

```
error: cannot find symbol
  import org.slf4j.impl.StaticLoggerBinder;
  ^
  symbol: class StaticLoggerBinder
```

LLM 分析：这是 SLF4J 1.x 的旧 API，需要用 SLF4J 2.x 的 `ServiceLoader` 机制。Teammate 自动修复，重新调用 `JavaBuild` 验证，编译通过。

---

## 第四阶段：Lead 汇总与验证

### 4.1 Lead 收取 Teammate 完成通知

Lead 的 `notificationFn` 每轮 Agent Loop 开始前调用 `TeammateRunner.drainLeadMailbox()`：

```
FileMailBox.readUnread("lead") 返回：
  - "[idle] migrate-service-layer: completed initial task"
  - "[idle] migrate-dao-layer: completed initial task"
  - "[idle] migrate-controller-layer: completed initial task"
```

这些消息以 system reminder 形式注入 Lead 的对话，LLM 看到三个 Teammate 都完成了。

### 4.2 Coordinator 模式验证

Lead 进入验证阶段，工具被限制为 `Coordinator.ALLOWED_TOOLS`（ReadFile、Bash、Glob、Grep、SendMessage 等）。

Lead 执行合并：

```bash
# Bash 工具
git -C .codepal/teams/migration-team/worktrees/agent-a3f7b12 add -A
git -C .codepal/teams/migration-team/worktrees/agent-a3f7b12 commit -m "migrate service layer"

git merge agent-a3f7b12 --no-ff -m "Merge: migrate service layer"
git merge agent-b8c9d34 --no-ff -m "Merge: migrate dao layer"
git merge agent-e5f6a78 --no-ff -m "Merge: migrate controller layer"
```

**发现冲突**：`UserRepository.java` 被 dao-layer 和 service-layer 都改了：

```
<<<<<<< HEAD
import org.apache.logging.log4j.LogManager;
=======
import org.slf4j.LoggerFactory;
>>>>>>> agent-b8c9d34
```

LLM 读到冲突标记，用 `EditFile` 解决（保留 SLF4J 版本），然后 `git add` + `git commit` 完成合并。

### 4.3 全量测试验证

```json
{"name": "JUnitRun", "input": {"filter": "all", "maxFailures": 10}}
```

`JUnitRunTool` 执行 `./gradlew test`，返回测试报告：

```
Tests run: 347, Failures: 2, Errors: 0
FAILED: LoggingIntegrationTest.testCustomAppender
FAILED: KafkaAppenderTest.testFlushOnShutdown
```

LLM 分析失败原因，找到 `CustomKafkaAppender` 的 `stop()` 方法在 Logback 里对应 `AppenderBase.stop()`，需要调用 `super.stop()`。修复后重跑，全部通过。

---

## 第五阶段：记忆提取与会话结束

### 5.1 记忆提取

每轮 Agent Loop 结束后，`MemoryManager.shouldExtract()` 返回 true（每轮触发）：

```java
// extract() 异步执行
String manifest = scanExistingMemories();
// manifest 列出已有记忆，传给 LLM 防止重复

// LLM 从最近 40 条消息提取：
```

LLM 输出：

```
MEMORY_NAME: project-logging-framework
MEMORY_TYPE: project
MEMORY_DESC: 项目已从 Log4j 2 迁移到 SLF4J + Logback
MEMORY_BODY: 迁移完成于 2026-08-26。CustomKafkaAppender 已重写为 Logback AppenderBase，
stop() 需要调用 super.stop()。SLF4J 版本使用 2.x，不用 StaticLoggerBinder。
---
MEMORY_NAME: feedback-ask-before-refactor
MEMORY_TYPE: feedback
MEMORY_DESC: 涉及自定义组件重写时先询问用户意图
MEMORY_BODY: 用户希望在遇到需要大幅重写（而非简单替换）的自定义组件时，先通过
AskUserQuestion 确认意图，不要擅自决定。
```

`writeMemoryFile()` 写入 `.codepal/memory/project-logging-framework.md`，更新 `MEMORY.md` 索引。

下次新会话时，MEMORY.md 被注入 System Prompt，LLM 直接知道项目用 SLF4J 2.x、有个坑在 `super.stop()`。

### 5.2 Metrics 报告

会话结束，`MetricsCollector.formatReport()` 输出：

```
Session Summary:
  Total turns: 47
  Total tokens: input=284,320 / output=18,940
  Tool calls: 156 (ReadFile:43, EditFile:31, Bash:28, Glob:12, JUnitRun:8, ...)
  Errors: 2 (Bash timeout x1, CompileError x1 → auto-recovered)
  Compact events: 3 (Layer1 spill x7, Layer2 summary x3)
  Wall time: 8m 42s
```

### 5.3 Hook SESSION_END

`HookEngine.fire(SESSION_END, ...)` 触发你配置的结束 hook：

```yaml
# .codepal/hooks.yaml
- event: SESSION_END
  action: command
  command: "echo '会话结束，共 ${TOOL_CALLS} 次工具调用' >> ~/.codepal/session.log"
```

### 5.4 Session 文件清理

`SessionManager.cleanExpiredSessions()` 扫描 `.codepal/sessions/`，删除 30 天前的 `.jsonl` 文件，释放磁盘空间。

---

## 完整数据流总结

```
用户输入
  ↓
TUI (CodePalModel) → 状态机管理交互
  ↓
Agent.agentLoop() → 无限循环
  ├── 每轮开始
  │   ├── notificationFn()        Teammate 完成通知注入
  │   ├── getAllSchemas()          工具列表（core + 已发现的 MCP）
  │   ├── addSystemReminder()     deferred 工具名 + Plan 模式提示
  │   ├── ToolResultBudget.apply() Layer 1：大结果落盘
  │   └── ContextCompactor.manage() Layer 2：接近上限时 LLM 摘要
  │
  ├── client.stream(conv, tools)  → Anthropic API
  │   └── 消费流式事件
  │       ├── TextDelta → TUI 实时渲染
  │       ├── ThinkingDelta → TUI 显示思考过程
  │       └── ToolCallComplete → 收集工具调用列表
  │
  ├── if (无工具调用) → LoopComplete，break
  │
  └── StreamingExecutor.executeAll(toolCalls)
      ├── partitionToolCalls()   READ 并行，WRITE/CMD 串行
      ├── PermissionChecker.check()  5 层权限过滤
      ├── HookEngine.fire(PRE_TOOL_USE)
      ├── Sandbox.wrap()         OS 级沙箱包装（可选）
      ├── tool.execute()         真正执行
      ├── HookEngine.fire(POST_TOOL_USE)
      └── conv.addToolResultsMessage()  结果追加对话历史

背后支撑
  ├── MemoryManager              每轮结束后异步提取记忆
  ├── SessionManager             每条消息落盘 .jsonl
  ├── MetricsCollector           每次工具调用记录延迟/错误
  ├── TraceStore                 调用链追踪
  └── RecoveryState              压缩后恢复最近文件读快照
```

---

## 真实开发环境中遇到的各类问题及处理方式

| 问题 | 触发场景 | 处理机制 |
|------|---------|---------|
| API 上下文超限 | 长任务积累大量工具结果 | Layer1 落盘 + Layer2 LLM 摘要 + PTL 重试 |
| 工具结果单条过大 | ReadFile 读 3000 行文件 | SINGLE_RESULT_LIMIT=50K，超出落盘只留指针 |
| 多并发工具结果聚合超大 | 并行读 6 个文件 | MESSAGE_AGGREGATE_LIMIT=200K，整条消息落盘 |
| 摘要请求本身超限 | 旧历史极长 | PTL 重试：从最旧消息组开始丢弃，最多 3 次 |
| 软触发失败 | LLM 超时/报错 | Circuit Breaker：连续 3 次失败停止软触发 |
| 硬触发后彻底崩溃 | 极端场景 | compact_boundary checkpoint，重启从最近摘要恢复 |
| tool_use/tool_result 配对错误 | 压缩边界切割不当 | computeKeepStartIndex() 自动后退到配对安全位置 |
| 危险命令 | rm -rf /、mkfs | Layer2 DANGEROUS_PATTERNS 直接 DENY |
| AI 给自己提权 | 写 permissions.yaml | DEFAULT_DENY_WRITE 保护配置文件路径 |
| 子 Agent 嵌套 fork | fork 内再 fork | FORK_BOILERPLATE_TAG 检测 + querySource 标记拦截 |
| Teammate 崩溃 | 进程意外退出 | 超时检测（60s 无 idle 通知）+ Lead 重分配任务 |
| 多 Teammate 文件冲突 | 分配失误 | git merge 冲突标记，Lead（LLM）手动解决 |
| MCP Server 连接失败 | 网络问题 | 单独 try-catch，部分可用不阻断启动 |
| Gradle 编译失败 | 依赖问题、API 变化 | JavaBuildTool 返回错误信息，LLM 分析修复再重试 |
| Token 估算偏差 | 中英文混合、Cache 命中 | UsageAnchor：每轮用真实 API usage 值校准，只估算增量 |
| 频繁弹窗 | 重复的工具调用 | allow-always Session 记忆，同类操作第一次后自动放行 |
| 用户忘记项目背景 | 新会话 | MemoryManager 两层存储，MEMORY.md 索引自动注入 |
| Skill 调用后上下文被压缩 | 长会话 | RecoveryState 记录 Skill 调用快照，压缩后重建 |
| 工具数量过多占满 Token | 接入 100+ MCP 工具 | shouldDefer=true，ToolSearch 按需加载 schema |

---

*（本文档覆盖 CodePal 所有核心模块，基于源码真实实现，可作为面试系统设计题的参考答案框架）*
