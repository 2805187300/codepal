# 亮点④：Lead-Teammate 多 Agent 并行协作

---

## 1. 为什么要做这个？——背景

### 1.1 单 Agent 串行的瓶颈

Agent 处理大型重构任务时，比如"把整个项目的日志框架从 Log4j 换成 SLF4J"，涉及几十个文件，每个文件都要：读文件 → 分析依赖 → 修改代码 → 验证编译。

单 Agent 的做法是一个文件一个文件串行处理：

```
文件A：读(2s) → 分析(3s) → 修改(2s) → 验证(5s) = 12s
文件B：读(2s) → 分析(3s) → 修改(2s) → 验证(5s) = 12s
文件C：...
...
30 个文件 × 12s = 360s ≈ 6 分钟
```

但这 30 个文件之间没有依赖关系，完全可以并行处理。如果 3 个 Agent 同时跑，理论上只需要 2 分钟。

**问题不是"能不能并行"，而是"怎么安全地并行"**——多个 Agent 同时写文件，怎么避免互相覆盖？怎么协调任务分配？怎么汇报进度？

---

### 1.2 整体架构：Lead 分解，Teammate 执行

```
用户
  ↓ 任务
Lead Agent（主进程）
  ├── 分析任务，拆分成独立子任务
  ├── 通过 FileMailBox 派发给 Teammate
  ├── 持续轮询 FileMailBox，收取进度汇报
  └── 汇总结果，返回给用户

Teammate-1（独立进程/线程）    Teammate-2         Teammate-3
  ├── 在独立 git worktree 里工作  ├── 独立 worktree   ├── 独立 worktree
  ├── 执行子任务                  ├── 执行子任务       ├── 执行子任务
  └── 完成后发消息给 Lead          └── 完成后发消息     └── 完成后发消息
```

三个关键设计：
1. **任务分解**：Lead 负责把大任务拆成互相独立的子任务
2. **文件级隔离**：每个 Teammate 在独立的 git worktree 里工作，物理上不共享文件系统
3. **FileMailBox 通信**：跨进程异步消息队列，基于文件系统实现

---

## 2. 文件级隔离：git worktree

### 2.1 为什么用 git worktree 而不是普通目录复制

多个 Agent 同时修改同一份文件，最后合并时会产生冲突。解决方案有两种：

| 方案 | 原理 | 问题 |
|------|------|------|
| 加锁 | 修改文件前先锁，改完解锁 | 并发变串行，性能回退 |
| git worktree | 每个 Agent 有独立的工作目录，共享 .git 仓库元数据 | 需要最后合并，但并发写入零冲突 |

`git worktree add` 可以从同一个 git 仓库创建多个独立的工作目录，每个目录在独立的 branch 上工作，彼此之间文件完全隔离：

```bash
# Lead 为每个 Teammate 创建独立的 worktree
git worktree add .codepal/teams/team1/worktrees/teammate-1 -b teammate-1-branch
git worktree add .codepal/teams/team1/worktrees/teammate-2 -b teammate-2-branch
git worktree add .codepal/teams/team1/worktrees/teammate-3 -b teammate-3-branch
```

Teammate-1 在 `worktrees/teammate-1/` 目录里修改文件，Teammate-2 在 `worktrees/teammate-2/` 里修改，两者互不干扰。最后 Lead 把各个 branch 合并回主分支。

### 2.2 效果

```
Teammate-1 修改 UserService.java（在自己的 worktree 里）
Teammate-2 修改 OrderService.java（在自己的 worktree 里）
Teammate-3 修改 PaymentService.java（在自己的 worktree 里）
  ↓ 三者完全并行，零写冲突
Lead 收集结果，合并分支
```

---

## 3. FileMailBox：跨进程异步通信

### 3.1 为什么不用共享内存或消息队列中间件

多 Agent 可以是同进程内的多线程（IN_PROCESS 模式），也可以是独立的 OS 进程（TMUX/ITERM 模式下每个 Teammate 是一个单独的终端进程）。

共享内存只能在同进程内用；Redis/Kafka 等消息队列引入了额外的基础设施依赖。

**文件系统是所有进程都能访问的天然共享介质**，用 JSON 文件作为消息队列，简单、无依赖、可持久化、可调试（直接 cat 文件就能看到消息）。

### 3.2 FileMailBox 的实现

每个 Agent 有一个收件箱文件：`.codepal/teams/{teamName}/inboxes/{agentId}.json`

```json
[
  {
    "from": "lead",
    "text": "请处理 UserService.java，把所有 log4j 调用替换成 slf4j",
    "timestamp": "2026-08-19T10:00:00Z",
    "read": false
  },
  {
    "from": "teammate-2",
    "text": "[idle] teammate-2: completed initial task",
    "timestamp": "2026-08-19T10:02:30Z",
    "read": false
  }
]
```

**并发安全靠文件锁**：多个进程同时写同一个 inbox 文件，用 `.lock` 文件实现互斥：

```java
// FileMailBox.java — withLock 方法
private void withLock(String agentId, MutationFn fn) {
    Path lock = lockPath(agentId);  // agentId.json.lock

    for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
        try {
            Files.createFile(lock);  // 原子创建，成功就拿到锁
            acquired = true;
            break;
        } catch (FileAlreadyExistsException e) {
            // 锁被别人持有，检查是否是超过 10 秒的陈旧锁
            if (/* 锁文件超过10秒 */) Files.deleteIfExists(lock);
            Thread.sleep(随机5-100ms);  // 随机退避，避免惊群
        }
    }
    // ... 拿到锁后读取-修改-写回，finally 删锁文件
}
```

`Files.createFile()` 在文件系统层面是原子操作（POSIX 保证），`FileAlreadyExistsException` 说明其他进程先拿到了锁，等待重试。

### 3.3 消息流转：从 Lead 到 Teammate 再回到 Lead

```
Lead 派发任务：
  team.sendMessage("lead", "teammate-1", "处理 UserService.java")
  → 写入 inboxes/teammate-1.json

Teammate-1 轮询收件箱（每 500ms 检查一次）：
  waitForNextPromptOrShutdown() → readUnread("teammate-1")
  → 拿到任务，执行 Agent Loop
  → 执行完毕，发送完成通知

Teammate-1 发送完成通知：
  team.sendMessage("teammate-1", "lead", "[idle] teammate-1: completed initial task")
  → 写入 inboxes/lead.json

Lead 收取通知（每次 Agent Loop 开始前）：
  TeammateRunner.drainLeadMailbox(teamMgr)
  → 读取 inboxes/lead.json，格式化成 system reminder
  → 注入到 Lead 的对话上下文
```

---

## 4. TeammateRunner：Teammate 的执行主循环

```java
// TeammateRunner.java — runInProcessTeammate（简化版）
public static void runInProcessTeammate(Team team, Member member,
                                         String initialPrompt, String addendum) {
    // 1. 注入角色信息（我是谁、团队是什么、如何通信）
    member.conv.addSystemReminder(addendum);

    // 2. 注入邮箱里待处理的消息
    injectPendingMessages(team, member.getName(), member.conv);

    // 3. 如果有初始任务，注入为用户消息
    if (initialPrompt != null && !initialPrompt.isEmpty()) {
        member.conv.addUserMessage(initialPrompt);
    }

    // 4. 运行 Agent Loop，处理当前任务
    var agentQueue = member.agent.run(member.conv);
    drainAgentEvents(agentQueue, eventOut, progress);

    // 5. 完成后通知 Lead
    team.sendMessage(member.getName(), LEAD_NAME,
            createIdleNotification(member.getName(), "completed initial task"));

    // 6. 等待 Lead 派发新任务，循环处理
    while (!Thread.currentThread().isInterrupted()) {
        var result = waitForNextPromptOrShutdown(team, member.getName());
        if (result.shutdown()) break;

        member.conv.addUserMessage(result.prompt());
        agentQueue = member.agent.run(member.conv);
        drainAgentEvents(agentQueue, eventOut, progress);

        team.sendMessage(member.getName(), LEAD_NAME,
                createIdleNotification(member.getName(), "completed follow-up"));
    }
}
```

关键点：Teammate 是一个"任务循环"——做完一个任务就通知 Lead，然后等待下一个，直到收到 `[shutdown]` 消息才退出。

---

## 5. 三种部署模式：IN_PROCESS / TMUX / ITERM

```java
// TeamManager.java — detectBackend()
public static TeamMode detectBackend() {
    if (/* Windows */) return TeamMode.IN_PROCESS;
    if (System.getenv("TMUX") != null) return TeamMode.TMUX;
    if (System.getenv("ITERM_SESSION_ID") != null) return TeamMode.ITERM;
    return TeamMode.IN_PROCESS;
}
```

| 模式 | Teammate 运行方式 | 适用场景 |
|------|------------------|---------|
| IN_PROCESS | 同一 JVM 的独立线程 | 轻量任务，调试方便 |
| TMUX | 独立终端窗格，独立进程 | 重型任务，视觉上可以看到每个 Teammate 在做什么 |
| ITERM | iTerm2 标签页 | macOS 开发者偏好 |

TMUX 模式的好处：每个 Teammate 有独立的终端窗格，可以实时看到它在执行什么，相当于并排开了多个 AI 工程师在同时工作，可观测性更好。

---

## 6. 60% 效率提升是怎么算的

```
假设：3 个模块，每个模块处理时间 10 分钟

串行（单 Agent）：
  模块A(10min) → 模块B(10min) → 模块C(10min) = 30 分钟

并行（3 个 Teammate）：
  模块A、B、C 同时跑 = 10 分钟（理想情况）
  加上 Lead 分解任务、收集结果的开销 ≈ 12 分钟

效率提升 = (30 - 12) / 30 ≈ 60%
```

这是三模块并行的理想估算。实际提升取决于任务的可并行度（有相互依赖的任务无法并行）和 Teammate 数量。

---

## 7. 面试怎么答

### 标准描述（30 秒版）

> "大型重构任务涉及几十个相互独立的文件改动，单 Agent 串行处理耗时过长。我设计了 Lead-Teammate 多 Agent 架构：Lead 分解任务后并行派发，各 Teammate 在独立 git worktree 内执行，文件级隔离避免并发写冲突；跨进程通信通过 FileMailBox 文件系统消息队列实现，基于文件锁保证并发安全，无需额外中间件依赖。
>
> 按典型三模块并行场景估算，大型重构任务处理效率提升约 60%。"

---

## 8. 大厂面试官高频追问（全集）

---

### 【设计决策类】

**Q1：为什么用 git worktree 而不是直接 git clone 多份仓库？**

> `git worktree` 和 `git clone` 的核心区别是：worktree 共享同一个 `.git` 目录（仓库元数据），clone 是完全独立的副本。
>
> 用 worktree 的好处：
> 1. **创建速度**：worktree add 几乎瞬间完成（只是创建一个指针），clone 要把整个 objects 数据库复制一遍，大仓库可能要几十秒
> 2. **磁盘占用**：worktree 只多出工作目录的文件，git 对象共享；clone 是完整副本，大仓库会翻倍占用磁盘
> 3. **合并方便**：worktree 创建在同一个仓库里的 branch，合并就是 `git merge`；clone 合并需要跨仓库操作
>
> 缺点：worktree 之间的 branch 不能重名，需要为每个 Teammate 生成唯一 branch 名。

**Q2：Lead 怎么决定把哪个子任务分给哪个 Teammate？有负载均衡吗？**

> 当前实现里任务分配是由 Lead（也就是 LLM）根据任务内容决定的，没有硬编码的负载均衡算法。Lead 会分析哪些文件需要改动，把大致等量的工作分给各 Teammate。
>
> 这种基于 LLM 判断的分配有一定智能性（比如把相关联的文件分给同一个 Teammate），但也有不确定性（LLM 可能分配不均）。更健壮的方案是加一个显式的任务调度层——维护一个任务队列，Teammate 完成当前任务后主动拉取下一个（工作窃取模式），但当前场景任务数量不大，LLM 分配够用。

**Q3：FileMailBox 用文件系统实现消息队列，性能够吗？轮询 500ms 会不会太慢？**

> 对于 Agent 任务来说，500ms 的轮询延迟完全可以接受。Agent 处理一个子任务通常需要几十秒到几分钟，500ms 的通知延迟相对任务本身的时间可以忽略不计。
>
> 如果是对延迟敏感的场景（比如实时协同），500ms 就太慢了，需要用真正的消息队列（Redis Pub/Sub、NATS 等）。但 Agent 框架的场景是"任务级协同"，不是"毫秒级通信"，文件系统队列的简单和无依赖性比低延迟更重要。

**Q4：SharedTaskStore 是做什么的？和 FileMailBox 有什么区别？**

> FileMailBox 是**点对点消息**，一条消息从发送方送到特定接收方，用于任务派发和进度汇报。
>
> SharedTaskStore 是**共享任务池**，Lead 把所有待处理任务写进去，Teammate 可以主动认领任务，适合任务数量多、需要动态调度的场景。类似一个"工单系统"：FileMailBox 是"私信"，SharedTaskStore 是"公告栏"。
>
> 两者配合：Lead 把任务写入 SharedTaskStore，通过 FileMailBox 通知 Teammate 去认领；Teammate 完成后更新 SharedTaskStore 里的任务状态，再通过 FileMailBox 汇报给 Lead。

---

### 【工程化细节类】

**Q5：文件锁用 `Files.createFile()` 的原子性来实现，这在 NFS 或网络文件系统上可靠吗？**

> `Files.createFile()` 的原子性依赖底层文件系统的实现。在本地文件系统（ext4、APFS、NTFS）上是可靠的；在 NFS 上由于网络延迟和缓存一致性问题，`FileAlreadyExistsException` 可能不够可靠。
>
> 代码里有一个超时保护——锁文件超过 10 秒没有被释放就认为是陈旧锁（持有者崩溃了）自动清除。这能处理大多数的锁泄漏场景，但在网络文件系统上的并发安全性不如本地文件系统。
>
> 当前设计假设 Teammate 在同一台机器上运行（哪怕是不同进程），文件系统是本地的，这个假设成立则文件锁是可靠的。

**Q6：Teammate 崩溃了怎么处理？Lead 怎么感知？**

> 当前的感知机制是**超时检测**：Teammate 完成任务后会发送 `[idle]` 消息给 Lead，Lead 在等待 Teammate 的时候如果超过一定时间没有收到 idle 通知，就认为这个 Teammate 可能出问题了。
>
> 代码里 `drainAgentEvents` 里有 60 秒超时：
>
> ```java
> event = source.poll(60, TimeUnit.SECONDS);
> if (event == null) return;  // 超时，认为结束了
> ```
>
> 崩溃恢复目前是人工介入——Lead 检测到 Teammate 没有响应后，可以把该 Teammate 的任务重新分配给其他 Teammate 或者自己处理。自动重试机制是改进方向。

**Q7：git worktree 最后怎么合并？合并冲突怎么处理？**

> 合并流程：
> 1. 每个 Teammate 在自己的 branch 上 commit
> 2. Lead 收到所有 Teammate 完成的通知后，依次 `git merge` 各个 branch
> 3. 如果有冲突（两个 Teammate 改了同一个文件的同一行），需要人工或 Lead 自动解决
>
> 为什么冲突不常见：任务分解时 Lead 会尽量把同一个文件的所有改动分给同一个 Teammate，避免同一个文件被多个 Teammate 修改。这是任务分解策略的一部分，而不是靠后期合并来解决冲突。

**Q8：IN_PROCESS 模式下多线程并发，PermissionChecker 和 ToolRegistry 是共享的还是独立的？**

> 每个 Teammate 的 `Agent` 实例是独立创建的（`new Agent(client, registry, protocol, cfg)`），`registry` 可以是共享的也可以是独立的，取决于调用方怎么传。
>
> PermissionChecker 是 Agent 内部持有的，每个 Agent 一份，互相独立。allowAlwaysRules 是 Agent 私有的，Teammate-1 允许的操作不会影响 Teammate-2。
>
> 共享 ToolRegistry 有线程安全问题，`discoveredTools` 用 `ConcurrentHashMap.newKeySet()` 保证了并发安全，`register/get` 也用 `ConcurrentHashMap`，整体是线程安全的。

---

### 【量化与验证类】

**Q9：60% 效率提升是怎么测出来的？**

> 这是基于三模块并行场景的理论估算：三个独立模块，每个 10 分钟，串行 30 分钟，并行约 12 分钟（含 Lead 开销），提升约 60%。
>
> 实测更严格的方法是：选取一批标准化的重构任务（文件数量、复杂度固定），分别在单 Agent 和 3-Teammate 两种配置下跑，计时对比。实测数字会受到 LLM 响应速度、任务分配质量、合并耗时等因素影响，实际提升可能在 40%-70% 之间浮动。

**Q10：怎么验证并行执行结果的正确性？多个 Teammate 修改后的代码能正确合并吗？**

> 正确性验证分两步：
>
> 1. **编译验证**：所有 Teammate 完成后，Lead 触发一次全量编译（`mvn compile` 或 `./gradlew build`），编译通过说明基本合并正确
> 2. **测试验证**：运行测试套件，确保功能没有回归
>
> 合并正确性的保障主要靠任务分解策略——Lead 在分派任务时尽量保证不同 Teammate 修改的文件集合不重叠，从源头避免合并冲突，而不是靠事后冲突解决。

---

### 【对比与扩展类】

**Q11：这个架构和 MapReduce 有什么相似和不同？**

> 确实很像 MapReduce：
>
> | 概念 | MapReduce | Lead-Teammate |
> |------|-----------|---------------|
> | 分解 | Map 函数把输入切片 | Lead 把任务分解成子任务 |
> | 并行执行 | 多个 Mapper 并行 | 多个 Teammate 并行 |
> | 汇总 | Reduce 函数聚合结果 | Lead 合并分支、汇总结果 |
> | 通信 | 中间文件（HDFS） | FileMailBox 文件消息队列 |
>
> 核心区别：MapReduce 的每个 Mapper 执行的是固定的函数，输入输出结构化；Lead-Teammate 的每个 Teammate 是一个完整的 AI Agent，执行的是自然语言描述的任务，远比 Mapper 灵活。

**Q12：如果 Teammate 数量继续增加（比如 10 个），FileMailBox 的文件锁会成为瓶颈吗？**

> 每个 Teammate 有独立的收件箱文件，锁的粒度是 per-inbox，10 个 Teammate 有 10 个独立的锁文件，写操作之间几乎没有竞争——瓶颈不在锁。
>
> Lead 的 inbox 是所有 Teammate 都往里写的，这里是汇聚点，可能有竞争。但 Teammate 发完成通知的频率很低（每个任务完成才发一次），10 个 Teammate 也不会形成明显的锁竞争压力。
>
> 真正的扩展瓶颈是 LLM API 的并发限制（大多数 API 有 RPM 限制），以及 git worktree 的合并复杂度随 Teammate 数量增长。10 个以上的 Teammate 需要考虑分批合并策略。

---

## 9. 简历一句话（背熟）

> **Lead-Teammate 多 Agent 并行协作**：Lead 分解任务后并行派发，各 Teammate 在独立 git worktree 内执行，文件级隔离避免并发写冲突，FileMailBox 文件系统消息队列实现跨进程异步通信；按典型三模块并行场景估算，大型重构任务处理效率提升约 60%。

---

---

## 10. 补充问答：FileMailBox 与文件冲突

**Q：FileMailBox 是自己实现的还是已有的中间件？**

> 完全自己实现，没有用任何消息队列中间件。整个 `FileMailBox.java` 只用了两个依赖：
> - **Jackson**（`ObjectMapper`）— 序列化/反序列化 JSON，项目本来就有的通用依赖
> - **Java NIO**（`java.nio.file.*`）— 文件读写，JDK 标准库
>
> 文件锁、随机退避、陈旧锁检测、消息格式全是手写的。这是一个设计亮点：**在不引入任何额外基础设施依赖的前提下，用文件系统实现了跨进程异步消息队列**，部署零成本，调试直接 `cat` 文件查看消息，对 Agent 框架这种低频通信场景完全够用。

**Q：多 Agent 并行修改代码，不会有文件冲突吗？**

> 不会，因为每个 Teammate 在**独立的 git worktree** 里工作，物理路径完全不同：
>
> ```bash
> git worktree add .codepal/teams/team1/worktrees/teammate-1 -b teammate-1-branch
> git worktree add .codepal/teams/team1/worktrees/teammate-2 -b teammate-2-branch
> ```
>
> Teammate-1 修改的是 `worktrees/teammate-1/UserService.java`，Teammate-2 修改的是 `worktrees/teammate-2/OrderService.java`——文件系统层面完全隔离，根本不存在同时写同一个文件的情况。
>
> 冲突风险转移到了合并阶段，但 Lead 在任务分解时就会从源头规避：
> ```
> Lead 分配原则：
>   把同一个文件的所有改动分给同一个 Teammate
>   → Teammate-1 负责所有涉及 UserService.java 的改动
>   → Teammate-2 负责所有涉及 OrderService.java 的改动
>   → 不同 Teammate 的修改文件集合尽量不重叠
> ```
>
> 如果真的有两个 Teammate 改了同一个文件的同一行，合并时才需要人工介入——这是 Lead 任务分解失误导致的，不是架构本身的问题。

**Q：Lead 是怎么分任务的？分任务之前它怎么知道不会冲突？**

> 项目里没有任何自动分析文件冲突的代码逻辑。Lead 怎么分任务、分给谁、分哪些文件——完全由 LLM 自己判断，框架只提供工具（`TeamCreate`、`Agent(team_name=...)`、`SendMessage`）和一句 prompt 指引。
>
> Lead 的实际流程是：先用 `ReadFile`/`Glob`/`Grep` 分析项目结构，理解哪些文件需要改动、改动之间是否有依赖，然后自己判断如何分配——"这三个类互相独立，可以并行；这两个类共享同一个接口，改动要给同一个 Teammate"。
>
> 这不是硬编码规则，是 LLM 读懂代码结构后的语义推理。优点是灵活，能理解规则代码无法表达的隐性依赖；缺点是不可靠，LLM 可能判断失误。这是有损优化——框架提供隔离机制（worktree），冲突规避依赖 LLM 的判断质量。

**Q：真的发生冲突了怎么处理？**

> 没有自动合并机制，由 Lead（LLM）在验证阶段处理。`Coordinator.java` 里定义了四阶段工作流：
>
> ```
> 1. Research       — Lead 探索问题空间
> 2. Synthesis      — Lead 制定计划、分解任务
> 3. Implementation — Lead 派发 Teammate 执行
> 4. Verification   — Lead 验证结果、解决冲突
> ```
>
> 第 4 阶段的具体流程：
> 1. 所有 Teammate 完成后发 `[idle]` 消息通知 Lead
> 2. Lead 用 `Bash` 执行 `git merge` 合并各 Teammate 的 branch
> 3. 有冲突时 `git merge` 输出冲突标记（`<<<<<<<`/`=======`/`>>>>>>>`），Lead 读到冲突内容
> 4. Lead 用 `ReadFile`/`EditFile` 分析冲突，选择保留哪一方或手动合并两份改动
> 5. 验证编译和测试通过
>
> Coordinator 模式下 Lead 的工具被限制为协调类工具（`ReadFile`、`Bash`、`SendMessage` 等），`Bash` 覆盖了 `git merge`，`EditFile` 覆盖了冲突编辑——和人工解决冲突的操作完全一样，只是执行者换成了 LLM。

*（本文档随学习对话持续更新）*
