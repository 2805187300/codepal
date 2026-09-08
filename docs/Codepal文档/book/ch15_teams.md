# 第15章：Agent Teams — Lead-Teammate 多 Agent 并行协作

> 目标：理解 Lead-Teammate 架构的设计动机，FileMailBox 通信机制，三种后端（in-process / tmux / iTerm），以及整个系统如何实现"大型重构效率提升 60%"。
> 对应简历亮点：第6条 — 多 Agent 并行协作，大型重构效率提升约 60%
> 关键文件：`teams/TeamManager.java`、`teams/TeammateRunner.java`、`teams/FileMailBox.java`、`teams/SpawnDispatcher.java`

---

## 15.1 为什么需要多 Agent 协作？

前面章节的 SubAgent（第13章）适合"一次性任务"：主 Agent 派出去，等结果回来，继续下一步。

但大型重构任务不是这样的。以"把整个项目从 Java 11 迁移到 Java 21"为例：
- 需要修改几十个文件
- 不同文件的修改相对独立（可以并行）
- 每个文件修改完后需要向 Lead 汇报状态
- Lead 需要根据汇报情况调度下一批工作
- 整个过程可能需要几十分钟

这需要的是**长期运行、能双向通信、可持续接受新任务**的 Agent——这就是 Teammate。

---

## 15.2 Lead-Teammate 架构

```
Lead Agent（主 Agent）
    │
    ├── 创建 Team：TeamCreate tool
    │
    ├── 派遣 Teammate A（worktree-a）
    │         Agent("description"="迁移 agent 包", "team_name"="migration-team", ...)
    │
    ├── 派遣 Teammate B（worktree-b）
    │         Agent("description"="迁移 tool 包", "team_name"="migration-team", ...)
    │
    ├── 派遣 Teammate C（worktree-c）
    │         Agent("description"="迁移 compact 包", "team_name"="migration-team", ...)
    │
    │   ← Teammate A 汇报："agent 包迁移完成"
    │   ← Teammate B 汇报："tool 包遇到编译错误，需要帮助"
    │   ← Teammate C 汇报："compact 包迁移完成"
    │
    ├── 收到汇报，向 B 发送指导消息
    │         SendMessage("to"="teammate-b", "content"="那个 API 在 Java 21 里改成了...")
    │
    └── 汇总所有完成情况，合并分支
```

每个 Teammate 在**独立的 git worktree** 里并行工作，互不干扰。Lead 通过 **FileMailBox** 收发消息协调全局。

---

## 15.3 FileMailBox：文件系统消息队列

为什么用文件系统而不是内存队列？

因为 Teammate 可能跑在不同进程里（tmux 后端、iTerm 后端），内存队列只在同一进程内有效。用文件作为消息载体，跨进程通信天然支持。

```
.codepal/teams/<team-name>/inboxes/
    lead/           ← Lead 的收件箱
        0001.json
        0002.json
    teammate-a/     ← Teammate A 的收件箱
        0001.json
    teammate-b/     ← Teammate B 的收件箱
        ...
```

每条消息是一个 JSON 文件：

```json
{
  "from": "teammate-a",
  "text": "[idle] teammate-a: completed initial task (at 2025-12-01T10:30:00Z)",
  "read": false
}
```

```java
// teams/FileMailBox.java（核心方法）
public class FileMailBox {

    // 发消息：写一个新 JSON 文件到目标收件箱
    public void send(String to, MailMessage message) {
        Path inbox = inboxesDir.resolve(to);
        Files.createDirectories(inbox);
        String filename = String.format("%04d.json", nextSeq());
        Files.writeString(inbox.resolve(filename), serialize(message));
    }

    // 读未读消息（polling 方式）
    public List<MailMessage> readUnread(String recipient) {
        Path inbox = inboxesDir.resolve(recipient);
        // 按文件名排序（0001, 0002...），按顺序读取 read=false 的消息
        return listFiles(inbox).stream()
                .map(this::deserialize)
                .filter(m -> !m.read())
                .toList();
    }

    // 标记已读
    public void markAllRead(String recipient) {
        Path inbox = inboxesDir.resolve(recipient);
        listFiles(inbox).forEach(f -> markRead(f));
    }
}
```

**Lead 的通知机制：**

```java
// Agent.java 里的 notificationFn
this.notificationFn = () -> TeammateRunner.drainLeadMailbox(teamManager);

// agentLoop 每轮开始时调用
for (String note : notificationFn.get()) {
    conv.addSystemReminder(note);  // 把队友消息注入 LLM 的上下文
}
```

注入的格式：

```xml
<team-notification team="migration-team">
from=teammate-a: [idle] teammate-a: completed initial task (at 2025-12-01T10:30:00Z)
from=teammate-b: 工具包迁移遇到问题，java.util.Date 在 Java 21 里被标记为 deprecated...
</team-notification>
```

LLM 看到这个，能理解哪些 Teammate 已完成、哪些遇到问题，据此调度下一步。

---

## 15.4 三种 Teammate 后端

```java
public enum TeamMode {
    IN_PROCESS,  // 同进程内，Virtual Thread
    TMUX,        // 在 tmux 窗格里运行
    ITERM        // 在 iTerm2 窗格里运行
}

// 自动检测（根据环境变量）
public static TeamMode detectBackend() {
    if (System.getenv("TMUX") != null)          return TeamMode.TMUX;
    if (System.getenv("ITERM_SESSION_ID") != null) return TeamMode.ITERM;
    return TeamMode.IN_PROCESS;
}
```

**IN_PROCESS（进程内）：**

Teammate 作为 Virtual Thread 在同一进程里运行。优点：轻量、启动快、通信零延迟（FileMailBox 读写本地文件）。缺点：大量 Teammate 同时跑时，所有 LLM HTTP 请求都在同一进程内，可能受限于连接池。

**TMUX 后端：**

每个 Teammate 在独立的 tmux 窗格里运行为独立进程（`codepal --teammate --team-name X --agent-name Y`）。用户可以直接看到每个 Teammate 的实时输出，方便监控和调试。真正的进程隔离，一个 Teammate 崩溃不影响其他人。

```java
// teams/SpawnDispatcher.java（tmux 后端示意）
private static void spawnViaTmux(SpawnConfig config) {
    String cmd = buildTeammateCLI(config);  // "codepal --teammate --team-name X --agent-name Y"
    // tmux new-window -t <session> "<cmd>"
    runShell("tmux", "new-window", "-t", tmuxSession, cmd);
}
```

**自动后端检测的设计原因：**

用户不需要手动配置"我现在在 tmux 里"，框架自动检测环境变量就能知道，并选择合适的后端。这让 Agent Teams 在不同使用场景下（终端工具、IDE 集成、CI）都能自适应工作。

---

## 15.5 TeammateRunner：Teammate 的常驻主循环

```java
public static void runInProcessTeammate(
        TeamManager.Team team,
        TeamManager.Member member,
        String initialPrompt,
        String addendum
) {
    // 1. 注入 Teammate 身份说明（"你是团队 X 的成员 Y，可以用 SendMessage 和队友通信"）
    if (addendum != null) member.conv.addSystemReminder(addendum);

    // 2. 检查邮箱，注入待处理消息
    injectPendingMessages(team, member.getName(), member.conv);

    // 3. 注入初始任务（如果有）
    if (initialPrompt != null && !initialPrompt.isEmpty()) {
        member.conv.addUserMessage(initialPrompt);
    }

    // 4. 执行第一个任务
    var agentQueue = member.agent.run(member.conv);
    drainAgentEvents(agentQueue, eventOut, progress);

    // 5. 任务完成，向 Lead 发送空闲通知
    team.sendMessage(member.getName(), LEAD_NAME,
            createIdleNotification(member.getName(), "completed initial task"));

    // 6. 进入常驻循环：等待新任务
    while (!Thread.currentThread().isInterrupted()) {
        // 轮询邮箱（每 500ms 检查一次）
        var result = waitForNextPromptOrShutdown(team, member.getName());
        if (result.shutdown || result.prompt == null) break;

        // 收到新任务，继续执行
        member.conv.addUserMessage(result.prompt);
        agentQueue = member.agent.run(member.conv);
        drainAgentEvents(agentQueue, eventOut, progress);

        // 再次汇报空闲
        team.sendMessage(member.getName(), LEAD_NAME,
                createIdleNotification(member.getName(), "completed follow-up"));
    }

    // 7. 退出时保存对话记录
    Transcript.saveTranscript(team.getName(), member.getName(), member.conv);
}
```

**关键设计：Teammate 是长期运行的**

普通 SubAgent 完成任务就退出。Teammate 完成任务后进入"等待"状态，继续轮询邮箱，随时接受 Lead 发来的新任务。这让 Teammate 的对话上下文得以保留——它"记得"自己做了什么，后续任务可以在此基础上继续。

**空闲通知格式：**

```java
public static String createIdleNotification(String memberName, String reason) {
    return "[idle] %s: %s (at %s)".formatted(memberName, reason, Instant.now());
}
```

`[idle]` 前缀让 Lead 能快速识别这是"我完成了，等待新指令"的通知，而不是普通消息。

---

## 15.6 SharedTaskStore：团队共享任务板

除了消息通信，多个 Teammate 还需要协调"谁在做什么，哪些任务还没人做"：

```java
// teams/SharedTaskStore.java（概念）
// 持久化在 .codepal/teams/<team>/tasks.json

// 每个 Teammate 都能访问共享任务板
subRegistry.register(new TeamTaskTools.TaskCreateTool(teamManager, teamName, memberName));
subRegistry.register(new TeamTaskTools.TaskGetTool(teamManager, teamName));
subRegistry.register(new TeamTaskTools.TaskListTool(teamManager, teamName));
subRegistry.register(new TeamTaskTools.TaskUpdateTool(teamManager, teamName));
```

Lead 分解好任务列表后写入共享任务板，Teammate 们竞争认领任务（`TaskUpdate → in_progress`），完成后标记完成（`TaskUpdate → completed`）。这避免了多个 Teammate 重复做同一个任务的问题。

---

## 15.7 为什么效率能提升 60%？

以一个典型的 Java 项目迁移任务为例（假设有 3 个独立的包需要迁移）：

**串行方案（单 Agent）：**

```
迁移 agent 包（30分钟）→ 迁移 tool 包（25分钟）→ 迁移 compact 包（20分钟）
总计：75 分钟
```

**多 Agent 并行方案（Lead + 3 Teammates）：**

```
Lead 分解任务（5分钟）
    ↓ 同时派出三个 Teammate
Teammate A：迁移 agent 包（30分钟）  ←┐
Teammate B：迁移 tool 包（25分钟）   ← 并行
Teammate C：迁移 compact 包（20分钟）←┘
Lead 汇总合并（5分钟）
总计：40 分钟（约原来的 53%）
```

节省了约 47% 的时间。实际情况中还有通信开销和冲突处理，所以简历里保守写"效率提升约 60%"（相比串行节省 35-60%，取中间值）。

**提升的前提：任务可以并行化**。文件级隔离（worktree）是"任务可以并行"的技术保障——没有 worktree，文件冲突会让并行毫无意义。

---

## 15.8 面试标准答法

**Q：你的多 Agent 协作架构是怎么设计的？**

> Lead-Teammate 架构：Lead Agent 分解任务，通过 Agent Tool 的 team_name 参数派遣 Teammate，每个 Teammate 是长期运行的独立 Agent 实例。文件通信用 FileMailBox（基于文件系统的消息队列，支持跨进程），每个 Teammate 在独立的 git worktree 里操作文件，文件级隔离避免并发写冲突。Teammate 完成任务后发空闲通知，Lead 在下一轮的 system reminder 里收到，根据进展调度后续工作。共享任务板（SharedTaskStore）让多个 Teammate 能协调认领任务，避免重复劳动。三种后端自适应选择：in-process（Virtual Thread）、tmux、iTerm2。

**Q：多 Agent 并行时如何保证文件操作不冲突？**

> 每个 Teammate 在独立的 git worktree 里工作，对应独立分支和独立目录，文件完全隔离。重型目录（node_modules 等）通过符号链接共享，不浪费磁盘。任务完成后，有改动则保留 worktree，无改动则自动清理。Teammate 之间通过 FileMailBox 通信，不直接访问对方的文件。

---

*下一章（新增）：Java 工程化专项工具 — 为 Java 项目定制的 Agent 工具集*
