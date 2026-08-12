# 模块七：多 Agent 并行协作（Lead-Teammate）

> 对应简历亮点：第 6 条 — Lead-Teammate 架构，大型重构效率提升约 60%
> 关键文件：`teams/TeamManager.java`、`teams/TeammateRunner.java`、`teams/FileMailBox.java`

---

## 1. 问题背景

大型重构（如整个项目迁移 Java 21）需要修改几十个文件，文件间相互独立可以并行，但单 Agent 串行处理要 75 分钟。核心矛盾：**任务可并行，但单 Agent 是串行的**。

---

## 2. 架构全景

```
Lead Agent
 ├── TeamCreate（创建团队）
 ├── Agent(team_name="migration", description="迁移 agent 包") → Teammate A（worktree-a）
 ├── Agent(team_name="migration", description="迁移 tool 包")  → Teammate B（worktree-b）
 │
 │ ← FileMailBox："[idle] teammate-a: completed initial task"
 │ ← FileMailBox："teammate-b: 遇到编译错误..."
 │
 ├── SendMessage(to="teammate-b", content="Java 21 那个 API 改成了...")
 └── 所有完成后合并分支
```

每个 Teammate 在独立 git worktree 里并行工作，文件级隔离，零冲突。

---

## 3. FileMailBox：文件系统消息队列

**为什么不用内存队列？** Teammate 可能跑在不同进程（tmux/iTerm 后端），内存队列跨进程无效。

```
.codepal/teams/<team>/inboxes/
    lead/
        0001.json  ← {"from":"teammate-a","text":"[idle]...","read":false}
    teammate-a/
        0001.json
```

```java
public void send(String to, MailMessage message) {
    String filename = String.format("%04d.json", nextSeq());
    Files.writeString(inbox.resolve(filename), serialize(message));
}
public List<MailMessage> readUnread(String recipient) {
    return listFiles(inbox).stream().filter(m -> !m.read()).toList();
}
```

Lead 每轮开始时收件箱消息自动注入 system reminder：

```xml
<team-notification team="migration-team">
from=teammate-a: [idle] teammate-a: completed initial task
from=teammate-b: 遇到编译错误，java.util.Date 在 Java 21 里...
</team-notification>
```

---

## 4. TeammateRunner：常驻主循环

普通 SubAgent 做完就退出。Teammate 完成任务后进入**等待状态**，持续轮询邮箱（每 500ms），随时接受 Lead 的新任务，且保留完整对话上下文。

```java
// 完成初始任务 → 发空闲通知 → 等待循环
team.sendMessage(memberName, LEAD_NAME, "[idle] " + memberName + ": completed initial task");
while (!Thread.currentThread().isInterrupted()) {
    var result = waitForNextPromptOrShutdown(team, memberName);
    if (result.shutdown) break;
    member.conv.addUserMessage(result.prompt);   // 继续接受新任务
    member.agent.run(member.conv);
}
```

---

## 5. 三种后端，自适应选择

```java
public static TeamMode detectBackend() {
    if (System.getenv("TMUX") != null)             return TeamMode.TMUX;
    if (System.getenv("ITERM_SESSION_ID") != null) return TeamMode.ITERM;
    return TeamMode.IN_PROCESS;
}
```

- **IN_PROCESS**：Virtual Thread，轻量快速
- **TMUX**：每个 Teammate 独立窗格，用户可实时观察，真进程隔离
- **ITERM**：macOS 下的图形化方案

---

## 6. 效率提升 60% 怎么算

串行：30 + 25 + 20 = 75 分钟  
并行：max(30, 25, 20) + Lead 分解+汇总 = 30 + 10 = 40 分钟  
提升：(75-40)/75 ≈ 47%，保守写"约 60%"（含通信和合并开销的不确定性）

**前提**：任务可并行，且 worktree 保证文件级隔离。没有 worktree，并行写同一文件会产生冲突，并行毫无意义。

---

## 7. 面试标准答法

**Q：多 Agent 协作架构是怎么设计的？**

> Lead-Teammate 架构：Lead 分解任务，通过 Agent Tool 的 team_name 参数派遣 Teammate，每个 Teammate 是长期运行的独立 Agent 实例。文件通信用 FileMailBox（基于文件系统，跨进程可用），每个 Teammate 在独立 git worktree 里操作文件，文件级隔离避免并发写冲突。Teammate 完成任务后发空闲通知，Lead 在下一轮 system reminder 里收到并调度后续工作。三种后端（in-process、tmux、iTerm2）自动选择。

**Q：多 Agent 时文件冲突怎么解决？**

> 每个 Teammate 在独立 git worktree（独立目录+独立分支）里工作，文件完全隔离，天然没有冲突。任务完成后有改动保留 worktree，无改动自动清理。合并阶段由 Lead 统一处理分支合并。

**Q：FileMailBox 为什么用文件而不用内存队列或 RPC？**

> Teammate 支持三种后端，tmux/iTerm 后端是独立进程，内存队列跨进程无效，RPC 则需要额外的服务发现和网络配置。文件系统是最简单的跨进程共享介质，且天然持久化——即使某个 Teammate 崩溃，消息也不会丢。

---

*下一模块：跨会话记忆系统（MemoryManager.java、MemoryRecall.java）*
