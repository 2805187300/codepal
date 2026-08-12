# 模块八：跨会话记忆系统

> 对应简历亮点：第 9 条 — 跨会话记忆系统，用户偏好跨会话持续生效
> 关键文件：`memory/MemoryManager.java`、`memory/MemoryRecall.java`、`memory/MemoryConsolidator.java`

---

## 1. 问题背景

每次启动新对话，LLM 失忆——不知道你是谁、不知道项目背景、不知道你的偏好。导致：
- 每次都要重新解释"这个项目用 Gradle，不用 Maven"
- 每次都要重新说"不要给代码加注释"
- 每次都要告知项目的技术决策背景

---

## 2. 两级存储分离

```
~/.codepal/memory/              ← 用户级（跨所有项目）
  MEMORY.md                     ← 索引：每条记忆一行指针
  user_profile.md               ← type: user
  feedback_no_comments.md       ← type: feedback

.codepal/memory/                ← 项目级（当前项目专属）
  MEMORY.md
  project_context.md            ← type: project
  reference_jira.md             ← type: reference
```

**类型路由规则**：`user/feedback` 存用户级（个人偏好跨项目通用），`project/reference` 存项目级（项目事实随仓库版本化）。

每条记忆是独立 `.md` 文件，带 YAML frontmatter（name/description/type）。MEMORY.md 只是索引，每行约 50 字符，注入 System Prompt 代价极低。

---

## 3. LLM 自动提取（MemoryManager.extract）

每轮对话结束后，用一个独立 LLM 调用分析最近 40 条消息：

```java
// 发送已有记忆 manifest 做去重（防止提取重复内容）
String manifest = scanExistingMemories();
// 输出格式：固定结构化文本，非 JSON（避免转义问题）
// MEMORY_NAME: kebab-case-name
// MEMORY_TYPE: user|feedback|project|reference
// MEMORY_BODY: 内容
// ---
```

解析输出，按 type 路由到对应目录，写入 `.md` 文件，追加 MEMORY.md 索引指针（已有就不重复追加）。

---

## 4. 语义召回（MemoryRecall）

每次用户提问前，用 selector LLM 从所有记忆的 description 中选出最相关的至多 5 条，注入 system-reminder：

```java
SELECTOR_SYSTEM_PROMPT:
  "选出对当前查询明确有用的记忆（至多 5 条）。
   如果不确定是否有用，不要包含。
   对于 recently-used tools 的 API 文档类记忆，不要选（已在用的工具不需要参考文档），
   但工具的已知 bug 和注意事项 要选。"
// 返回 JSON：{"selected_memories": ["filename1.md", "filename2.md"]}
```

已曝光的记忆不重复注入（`alreadySurfaced` 过滤），配合 `MemoryAge` 对老旧记忆加"可能已过期"标注。

**关于"语义搜索"**：CodePal 的记忆通常只有几十条，用 LLM 读 description 做判断就够了，不需要向量检索。向量检索适合上千条以上的规模，为几十条引入 embedding 模型和向量库是过度工程。**方案要匹配规模**。

---

## 5. autoDream：后台记忆整理（MemoryConsolidator）

随着自动提取积累，记忆会出现重复、过时、矛盾。`autoDream` 周期性清理：

**触发三道门**：
- 时间门：距离上次整理 > 24 小时（API 有成本，不能太频繁）
- 会话门：自上次整理以来 ≥ 5 个会话（太少说明变化不大）
- 节流：10 分钟内不重复触发

**文件锁防并发**（基于 PID 回读验证）：
```java
// 写入自己 PID → 回读验证是否是自己的 PID → 两进程都写时只有后写的能拿到锁
Files.writeString(lockPath, String.valueOf(ProcessHandle.current().pid()));
String verify = Files.readString(lockPath).trim();
if (Long.parseLong(verify) != ProcessHandle.current().pid()) return null; // 放弃
```

整理工作交给子 Agent 执行（BYPASS 权限，可自由读写记忆文件），最多 15 轮，分 4 阶段：Orient → Gather（检查信息漂移）→ Consolidate（合并/修正/删除）→ Prune（更新索引，保持 ≤200 行）。

---

## 6. 面试标准答法

**Q：跨会话记忆系统是怎么设计的？**

> 两级存储（用户级/项目级）分离，每条记忆是独立 Markdown 文件，MEMORY.md 作索引。每轮对话后用独立 LLM 调用自动从最近 40 条消息提取记忆，含已有记忆 manifest 做去重。查询时 selector LLM 从所有记忆 description 中选最相关的至多 5 条注入上下文，已曝光的跳过。MEMORY.md 超过 200 行时触发 autoDream 后台整理（子 Agent + 文件锁）。

**Q：为什么不用向量数据库做召回？**

> 方案要匹配规模。记忆通常就几十条，用 LLM 读 description 做判断已经足够精准，引入 embedding 模型和向量库是过度工程。到了上千条量级再考虑升级。CodePal 当前的 selector LLM 调用比向量检索更灵活，能理解"不要选 recently-used tools 的 API 文档"这类语义约束。

**Q：记忆自动提取有什么问题，怎么防止？**

> 两类风险：一是提取过多（把临时任务状态也存成记忆），靠 extract prompt 里的"不该存"黑名单和 manifest 去重约束；二是提取到敏感信息（密码、API Key），靠 prompt 明确列出禁止存储的类型，生产系统还应加正则扫描（匹配 `sk-`/`ghp_` 等格式）。核心原则是宁可少记，不确定就不记。

**Q：autoDream 文件锁为什么用 PID 回读验证？**

> 两进程可能同时通过"检查锁不存在"那一步，都去写自己的 PID。文件系统写是"后写覆盖"，锁文件里只留最后写入的 PID。两进程各自回读，只有最后写入的那个能读到自己的 PID 并拿到锁，另一个读到别人的 PID 主动放弃。这不是严格原子锁，但对"整理失败无害"的低频场景足够，是典型的"方案匹配场景"。

---

*至此学习路线主要模块全部覆盖。*
