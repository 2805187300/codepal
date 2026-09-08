# 第9章：记忆系统 — 跨会话的 Agent 长期记忆

> 目标：理解 Agent 的记忆是怎么存储的、怎么自动提取的、跨会话如何复用，以及"autoDream"整理机制的设计。
> 关键文件：`memory/MemoryManager.java`、`memory/MemoryConsolidator.java`

---

## 9.1 为什么 Agent 需要长期记忆？

每次启动一个新对话，LLM 都是"失忆"状态——不知道你是谁、不知道你们上次讨论了什么、不知道你有什么偏好。

对于 Coding Agent 来说，这意味着：
- 每次都要重新解释"我们这个项目用 Gradle，不用 Maven"
- 每次都要重新说"你之前帮我改的那个 bug 是因为 null 检查顺序不对"
- 每次都要重新告知"不要给我的代码加注释，我不喜欢"

**长期记忆系统**的目标：把对话里有价值的信息自动沉淀下来，下次启动时直接加载，让 Agent 像一个真正的工作伙伴，记得你、记得项目、记得之前的决策。

---

## 9.2 记忆的存储格式：文件即记忆

CodePal 的记忆不存在数据库里，而是**每条记忆一个 Markdown 文件**，加上一个索引文件 `MEMORY.md`：

```
~/.codepal/memory/              ← 用户级（跨项目通用）
  MEMORY.md                     ← 索引文件，每条记忆一行指针
  user_profile.md               ← type: user（关于用户本身的信息）
  feedback_testing.md           ← type: feedback（用户给的工作方式偏好）

.codepal/memory/                ← 项目级（只属于这个项目）
  MEMORY.md
  project_context.md            ← type: project（项目背景、当前进展）
  reference_api_docs.md         ← type: reference（外部资源指针）
```

**每个记忆文件的格式**：

```markdown
---
name: user-prefers-no-comments
description: 用户不喜欢代码注释，除非逻辑非常复杂
metadata:
  type: feedback
---

用户明确说过：不要在代码里加注释。只有在逻辑涉及非常规约束或隐藏 bug 修复时才加一行。

**Why:** 用户认为好的命名已经足够表达意图，注释是代码不够清晰的替代品。
**How to apply:** 所有代码修改默认不加注释；遇到真正需要说明的地方，先问用户。
```

**记忆类型的分层**：

| 类型 | 存储位置 | 含义 |
|------|---------|------|
| `user` | `~/.codepal/memory/` | 用户个人偏好、背景信息（跨项目） |
| `feedback` | `~/.codepal/memory/` | 用户对工作方式的反馈（跨项目） |
| `project` | `.codepal/memory/` | 项目进展、背景、决策（项目专属） |
| `reference` | `.codepal/memory/` | 外部资源位置（项目专属） |

`user` 和 `feedback` 存用户级，因为"我不喜欢注释"这个偏好在所有项目里都适用。`project` 和 `reference` 存项目级，因为"这个项目用 PostgreSQL 15"只属于这个项目。

---

## 9.3 MEMORY.md 索引：信息密度的关键

```java
// MemoryManager.buildSystemReminder() 的输出示例：

# auto memory

## User-level MEMORY.md (`~/.codepal/memory/MEMORY.md`)

- [User Profile](user_profile.md) — 后端开发者，5 年 Java 经验，不熟悉 React
- [Feedback Testing](feedback_testing.md) — 不要 mock 数据库，用真实 DB 测试

## Project-level MEMORY.md (`.codepal/memory/MEMORY.md`)

- [Project Context](project_context.md) — CodePal Java Agent 框架，学习项目
- [Feedback Learning](feedback_learning.md) — 学习记录写到 docs/，讲解侧重亮点
```

**为什么要索引文件，而不是直接把所有记忆内容都注入？**

`MEMORY.md` 只有几十行，注入到 System Prompt 代价很低（几百 token）。如果把所有记忆文件的全文都注入，可能几千甚至几万 token，每轮都要发送，成本很高。

索引文件的设计让 LLM 先"知道有什么"，需要某条具体记忆的详细内容时，再用 `ReadFile` 读取对应的 `.md` 文件。这是"目录 + 按需翻页"的模式，比"全文一次性加载"高效得多。

MEMORY.md 有一个硬性约束（来自 `MemoryConsolidator`）：
```java
private static final int MAX_ENTRYPOINT_LINES = 200;
```

超过 200 行说明记忆太碎，需要整理。整理的过程就是第9.5节的 autoDream。

---

## 9.4 记忆提取：LLM 自动从对话里提炼

记忆不是手动写的，而是通过 `MemoryManager.extract()` 自动从对话历史中提取：

```java
public void extract(LlmClient client, ConversationManager conv) {
    List<Message> messages = conv.getMessages();
    if (messages.size() < 4) return;  // 太短的对话不提取

    // 只取最近 40 条消息，避免发送太多 token
    int start = Math.max(0, messages.size() - 40);
    var sb = new StringBuilder();
    for (int i = start; i < messages.size(); i++) {
        sb.append('[').append(msg.getRole()).append("]: ").append(msg.getContent()).append('\n');
    }

    // 扫描已有记忆做去重（防止重复提取相同内容）
    String manifest = scanExistingMemories();
    String manifestSection = manifest.isEmpty() ? "" :
        "\n\n## Existing memory files\n\n" + manifest +
        "\n\nCheck this list before creating — update an existing file rather than creating a duplicate.";

    // 构建提取请求，发给 LLM
    extractConv.addUserMessage(
        "Analyze the conversation below and extract memories worth saving.\n\n"
        + "For each memory, output in this exact format:\n"
        + "MEMORY_NAME: <kebab-case-name>\n"
        + "MEMORY_TYPE: <user|feedback|project|reference>\n"
        + "MEMORY_DESC: <one-line description>\n"
        + "MEMORY_BODY: <content>\n"
        + "---\n\n"
        + "What NOT to save:\n"
        + "- Code patterns derivable from reading the project\n"
        + "- Git history, debugging solutions\n"
        + "- Ephemeral task details\n\n"
        + "If nothing is worth saving, output NONE."
        + manifestSection + "\n\n"
        + "Conversation:\n" + sb
    );
    // ... 调用 LLM，解析输出，写入文件
}
```

**LLM 的输出格式**是固定的结构化文本（不是 JSON，避免转义问题）：

```
MEMORY_NAME: user-prefers-gradle
MEMORY_TYPE: feedback
MEMORY_DESC: 用户这个项目用 Gradle，不用 Maven
MEMORY_BODY: 项目使用 Gradle（build.gradle.kts），不使用 Maven。
相关命令：./gradlew build、./gradlew test
---
MEMORY_NAME: project-context
MEMORY_TYPE: project
MEMORY_DESC: CodePal Java Agent 框架学习项目
MEMORY_BODY: 正在学习 CodePal 项目，目标是...
---
```

解析逻辑：

```java
// 按 "---" 分割成多个 block，每个 block 是一条记忆
for (String block : output.split("---")) {
    if (!block.contains("MEMORY_NAME:")) continue;
    String name = extractField(block, "MEMORY_NAME");   // 用正则提取每个字段
    String type = extractField(block, "MEMORY_TYPE");
    String desc = extractField(block, "MEMORY_DESC");
    String body = extractField(block, "MEMORY_BODY");
    if (name.isEmpty() || body.isEmpty()) continue;

    // 根据 type 决定写入哪个目录
    Path targetDir = USER_TYPES.contains(type) ? userMemDirPath : projectMemDirPath;
    writeMemoryFile(targetDir, name, type, desc, body);
}
```

**`writeMemoryFile` 做了两件事：**

1. 把记忆内容写成 `name.md` 文件（带 YAML frontmatter）
2. 在 `MEMORY.md` 索引里追加一行指针（如果还没有的话）

```java
private void writeMemoryFile(Path dir, String name, String type, String description, String body) {
    String fileContent = "---\nname: %s\ndescription: %s\nmetadata:\n  type: %s\n---\n\n%s\n"
            .formatted(name, description, type, body);
    Files.writeString(filePath, fileContent);

    // 追加索引指针
    String pointer = "- [%s](%s) — %s\n".formatted(name, filename, description);
    String existing = Files.exists(entrypoint) ? Files.readString(entrypoint) : "";
    if (!existing.contains(filename)) {  // 防重复：文件名已在索引里就不再追加
        Files.writeString(entrypoint, existing + pointer);
    }
}
```

---

## 9.5 autoDream：后台记忆整理

随着对话越来越多，自动提取的记忆可能出现：
- **重复**：同一件事在两个文件里写了两遍
- **过时**：上个月说"截止日期是 12 月"，现在已经过了
- **矛盾**：以前记录"用 Java 11"，现在项目升到了 Java 21

`MemoryConsolidator` 的 `autoDream` 机制负责周期性地清理这些问题：

```java
// 触发条件：满足时间门和会话门，才执行整理
public void maybeRun(LlmClient client, ConversationManager conversation, String protocol) {
    // 时间门：距离上次整理超过 24 小时
    double hoursSince = (System.currentTimeMillis() - readLastConsolidatedAt()) / 3_600_000.0;
    if (hoursSince < minHours) return;   // DEFAULT_MIN_HOURS = 24

    // 节流：距离上次扫描超过 10 分钟（防止同一小时内反复触发）
    if (now - lastScanAt < SCAN_THROTTLE_MS) return;

    // 会话门：自上次整理以来完成了至少 5 个会话
    List<String> sessionIds = listSessionsSince(lastAt);
    if (sessionIds.size() < minSessions) return;  // DEFAULT_MIN_SESSIONS = 5

    // 获取锁，防止多个进程并发整理
    Long priorMtime = tryAcquireLock();
    if (priorMtime == null) return;

    // 后台执行，不阻塞主 Agent
    Thread.startVirtualThread(() -> {
        run(client, conversation, protocol, sessionIds);
    });
}
```

**三道门控的设计原因：**

- **时间门（24h）**：记忆整理本身要调 LLM，有 API 成本，不能太频繁
- **会话门（5 sessions）**：会话太少说明变化不大，整理意义不大
- **节流（10min）**：防止 `maybeRun` 在某个特殊时间点被频繁检查

**锁机制（文件锁）防止并发整理：**

```java
private Long tryAcquireLock() {
    Path path = lockPath();   // .codepal/memory/.consolidate-lock

    // 检查已有锁
    if (Files.exists(path)) {
        long mtimeMs = Files.getLastModifiedTime(path).toMillis();
        long holderPid = Long.parseLong(Files.readString(path).trim());

        // 如果锁文件新鲜（1小时内）且持有者进程还在运行，则放弃
        if (System.currentTimeMillis() - mtimeMs < HOLDER_STALE_MS &&
                isProcessRunning(holderPid)) {
            return null;  // 别人正在整理
        }
    }

    // 写入当前进程 PID，声明锁
    Files.writeString(path, String.valueOf(ProcessHandle.current().pid()));

    // 回读验证（防止写入被覆盖）
    String verify = Files.readString(path).trim();
    if (Long.parseLong(verify) != ProcessHandle.current().pid()) return null;

    return priorMtime;
}
```

这是一个基于文件的分布式锁（适合单机多进程场景）：
1. 写入自己的 PID 到锁文件
2. 回读验证确实是自己的 PID（最终一致性检查）
3. 检查已有锁是否"过期"：超过 1 小时且持有者进程不在了，认为锁已失效

**`isProcessRunning` 用 Java 9+ 的 `ProcessHandle`**：

```java
private static boolean isProcessRunning(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
}
```

`ProcessHandle.of(pid)` 返回 Optional，如果进程不存在返回 empty，`orElse(false)` 确保不存在时返回 false（进程已停止）。

**整理的 prompt（分四阶段）**：

```
Phase 1 — Orient：读 MEMORY.md 索引，扫描现有记忆，避免重复创建
Phase 2 — Gather：查找信息漂移（代码里的内容和记忆里写的不一致）
Phase 3 — Consolidate：合并重复记忆，修正过时内容，删除矛盾内容
Phase 4 — Prune：更新 MEMORY.md，保持在 200 行以内，每行 < 150 字符
```

整理工作交给一个**子 Agent** 来做（权限模式 BYPASS，可以自由读写记忆文件）：

```java
Agent subAgent = new Agent(client, registry, protocol, cfg);
subAgent.setChecker(new PermissionChecker(PermissionMode.BYPASS, Path.of(workDir)));
subAgent.setMaxIterations(15);  // 最多 15 轮，防止跑偏

// 驱动子 Agent 到完成
BlockingQueue<AgentEvent> queue = subAgent.run(conv);
while (true) {
    AgentEvent event = queue.take();
    if (event instanceof AgentEvent.LoopComplete) break;
    if (event instanceof AgentEvent.ErrorEvent) break;
}
```

**为什么用子 Agent 而不是直接调 LLM？**

整理记忆需要读多个文件、写多个文件、更新索引，这本身就是一个多步骤的任务。用子 Agent，整理过程能自适应调整（读文件发现更多内容，调整整理计划），比单次 LLM 调用更灵活。

---

## 9.6 非阻塞记忆召回（Prefetch）

启动时加载完整的 MEMORY.md 索引就够了，但对话进行中，LLM 可能在某个工具执行完后需要联想到某段相关记忆。

`Agent.java` 里有一段非阻塞记忆召回的逻辑：

```java
// 在 Agent 发起 LLM 请求时，同步在后台开始召回记忆
this.memoryRecallFuture = CompletableFuture.supplyAsync(() -> {
    return memoryRecall.recall(userInput, conv);  // 语义搜索相关记忆
});

// 等工具执行完后，检查 prefetch 是否就绪
if (memoryRecallFuture != null && !memoryRecallConsumed && memoryRecallFuture.isDone()) {
    String recall = memoryRecallFuture.getNow("");
    if (recall != null && !recall.isEmpty()) {
        conv.addSystemReminder(recall);  // 注入到对话历史
    }
    memoryRecallConsumed = true;
}
```

**时序设计**：记忆召回和 LLM 第一轮调用是**并行**的。等 LLM 返回、工具执行结束（通常 2-5 秒），记忆召回也差不多结束了，这时注入不增加额外延迟。

**`recall` 内部到底是怎么"语义搜索"的？**（新手最想知道、面试也必问的点）

这里的"语义搜索"不一定要上向量数据库。记忆召回的实现有一个从轻到重的谱系，取决于记忆规模：

| 方案 | 实现 | 适用规模 | 代价 |
|------|------|---------|------|
| **关键词匹配**（最轻）| 从 userInput 抽关键词，去匹配记忆文件的 description/tags | 几十条记忆 | 零额外依赖，零延迟 |
| **BM25/TF-IDF** | 用倒排索引做文本相关性打分 | 几百条 | 需要建索引，但无需模型 |
| **向量检索**（最重）| 用 embedding 模型把 userInput 和每条记忆编码成向量，算余弦相似度取 Top-K | 上千条以上 | 需要 embedding 模型 + 向量存储 |

CodePal 的记忆规模通常只有几十条（一个项目的偏好、进展就那么多），**关键词/文本匹配就够了**，没必要为几十条记忆引入 embedding 模型和向量库——那是过度工程。面试时如果被追问"为什么不用向量检索"，标准答法是：**方案要匹配规模**。记忆到了成千上万条（比如做一个跨大量项目的通用助手）再上向量检索也不迟。这本身就是一个很好的技术判断力展示点。

**记忆召回 = 一种 RAG**

值得点破的是：记忆召回本质上就是 **RAG（检索增强生成）**——检索（从记忆库找相关记忆）+ 增强（注入到上下文）+ 生成（LLM 带着记忆回答）。只不过：
- 检索的"知识库"是记忆文件，不是文档；
- 检索方式是文本匹配，不是向量（因为规模小）；
- 注入时机是"工具执行后异步补充"，不是"请求前一次性检索"。

所以你完全可以说"我实现过一个轻量级 RAG"——面试官问 RAG 时，这是你的实战切入点（哪怕你没搭过 Pinecone）。

---

## 9.7 面试官可能问的问题

**Q1：你们的记忆系统是怎么设计的？**

> 每条记忆一个独立 Markdown 文件，带 YAML frontmatter（name/description/type）。分两级：用户级（`~/.codepal/memory/`）存 user/feedback 类记忆，跨项目通用；项目级（`.codepal/memory/`）存 project/reference 类记忆，项目专属。每个目录有 MEMORY.md 索引文件，注入到 System Prompt 时只加载索引（几百 token），需要细节时 ReadFile 按需加载（避免全量注入几千 token）。对话结束后，调 LLM 从对话里自动提取值得保留的信息写成记忆文件。

**Q2：autoDream 是什么？为什么需要它？**

> autoDream 是后台记忆整理机制：满足时间门（24h）和会话门（5 sessions）后，启动一个子 Agent 对现有记忆做整理——合并重复、修正过时、删除矛盾、更新索引。需要它是因为自动提取的记忆久了会碎片化（多条记忆说的是同一件事），索引超过 200 行会影响效率。整理本身是复杂的多步骤任务（读多个文件、写多个文件），用子 Agent 比单次 LLM 调用更灵活。用文件锁防止多进程并发整理。

**Q3：记忆召回是怎么做的？为什么不用向量数据库？**

> 记忆召回本质是一种轻量 RAG——从记忆库检索相关记忆，注入上下文。检索方式取决于规模：CodePal 的记忆规模是几十条（一个项目的偏好、进展），用关键词/文本匹配就够了，零额外依赖、零延迟。向量数据库适合成千上万条文档的场景，为几十条记忆引入 embedding 模型和向量库是过度工程。方案要匹配规模——这是核心判断。记忆到了上千条量级再上向量检索也不迟。

**Q4：autoDream 的文件锁"回读验证"为什么能防并发？两个进程同时写会怎样？**

> 这是防 TOCTOU（Time-Of-Check-To-Time-Of-Use）竞态的经典手法。两个进程可能同时通过"检查锁不存在"这一步，然后都去写自己的 PID。关键在**回读验证**：写完后再读一次锁文件，比对里面的 PID 是不是自己的。文件系统的写是"后写覆盖"，两个进程都写完后，锁文件里只会剩最后写入的那个 PID；两个进程各自回读，只有一个能读到自己的 PID、拿到锁，另一个读到对方的 PID、主动放弃。这不是原子锁（真要严格得用 `Files.createFile` 的原子创建或文件系统级 flock），但对"记忆整理"这种低频、失败也无害（大不了这次不整理）的场景，回读验证足够了。又是一个"方案匹配场景"的例子。

**Q5：记忆越存越多会怎样？如何防止记忆膨胀拖累每轮 token？**

> 两层控制。第一层是**索引长度硬上限**：MEMORY.md 超过 200 行就触发 autoDream 整理，因为索引是每轮都要注入 System Prompt 的，它不能无限膨胀。第二层是**索引 + 按需加载的分离**：记忆全文不进上下文，只有几十字的索引行进，真正的内容躺在 `.md` 文件里，LLM 需要时才 ReadFile。所以哪怕有 100 条记忆，每轮固定成本也就是 200 行索引。膨胀的压力被 autoDream 的合并/剪枝挡在了索引层之外。

**Q6：怎么保证提取的记忆不包含敏感信息（密码、密钥）？**

> 这是个真实风险——对话里可能出现 `password=secret123`、API key 等，如果被当成"项目配置"提取进记忆文件，还可能被 commit 进 git 泄露。防护要多层：一是 extract prompt 里明确列入"不该存"的黑名单（凭证、密钥、token、个人隐私）；二是提取后对记忆内容做正则扫描（匹配常见密钥格式如 `sk-`、`ghp_`、长十六进制串），命中就拒绝写入或脱敏；三是 `.codepal/memory/` 加入 `.gitignore` 的判断（用户级记忆本就不该进仓库）。**注意**：本章 9.4 的示例里出现了 `secret123`，那是反面教材——真实系统绝不该把它存进记忆。

---

## 9.8 生产中可能遇到的问题

**问题1：记忆提取质量不稳定，提取出的内容太宽泛**

根本原因：extract prompt 里的 "What NOT to save" 规则不够具体，LLM 把临时性的任务进度也存成了记忆。

解法：在 extract prompt 里加更多"不该存"的例子，尤其是"当前任务的步骤和状态"这类高频误提取场景。

**问题2：autoDream 修改了某条重要记忆，导致行为变化**

autoDream 的子 Agent 有写权限，可能在"整合"过程中修改或删除了某条原本准确的记忆。

解法：
1. 整理前自动 `git add + git commit` 记忆目录（如果在 git 仓库里）
2. autoDream prompt 里明确说"如果不确定某条记忆是否过时，保留而不是删除"

**问题3：用户级记忆路径（`~/.codepal/memory/`）在 CI 环境里不存在**

CI 环境通常没有 home 目录下的个人配置。`ensureDir` 会创建这个目录，但 CI 每次都是干净环境，记忆无法持久化。

解法：CI 场景应该只用项目级记忆（`.codepal/memory/`），提交到 git 仓库，这样 CI 每次能读到项目记忆。

**问题4（用户视角）：Agent 记住了用户不希望它记的东西**

用户随口说了句"我最近在面试跳槽"，被提取成了 user 记忆，之后每个项目都带着这条——用户会觉得被冒犯、有隐私顾虑。或者记住了一个临时的错误决定（"这次先用 hack 方案"），后续项目里还在遵循。

解法：一是提取要保守（宁可少记，不确定就不记）；二是给用户**透明和控制权**——`/memory list` 能看到记了什么，`/memory clear` 能删。记忆系统最忌讳"黑箱"——用户不知道 Agent 记了什么、为什么表现和上次不一样，会失去信任。可发现 + 可编辑是记忆系统的体验底线。

**问题5（用户视角）：团队共享项目里，个人偏好泄露给同事**

项目级记忆（`.codepal/memory/`）如果 commit 进 git，会被团队所有人共享。张三的"我喜欢用 4 空格缩进"被提交后，李四拉下来发现 Agent 莫名其妙按张三的偏好工作。

解法：区分清楚——**个人偏好（feedback/user）永远存用户级**（不进 git），**项目事实（project/reference）才存项目级**（可进 git）。类型分层（9.2 节）不只是组织方式，更是隐私边界。提取时把类型判断做准，是防止个人偏好泄露的关键。

---

*下一章：Slash Command — 内置命令系统的实现*
