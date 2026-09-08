# 亮点⑤：跨会话记忆系统

---

## 1. 为什么要做这个？——背景

### 1.1 LLM 的"失忆"问题

LLM 没有持久记忆——每次新对话，它完全不知道上一次聊了什么。对于普通聊天这不是大问题，但对于 Agent 来说很致命：

```
第一天：用户告诉 Agent
  - "我们项目用 Gradle，不要用 Maven"
  - "代码风格要求方法名用驼峰命名"
  - "生产环境配置在 /prod/config.yaml，不要动它"

第二天：用户开新对话
  Agent 完全失忆，用户需要重新说一遍
  第三天又要说一遍...
```

这不只是烦，在实际工程场景里"用户忘记说"或者"以为 Agent 记得"就会出事——Agent 用 Maven 构建失败、命名风格不统一、误操作了生产配置。

### 1.2 两层存储：用户级 vs 项目级

记忆有两种性质：

| 性质 | 举例 | 应该存在哪里 |
|------|------|------------|
| 用户偏好（跨项目通用） | "我喜欢简洁的代码风格"、"不要加多余注释" | 用户目录 `~/.codepal/memory/` |
| 项目上下文（跟随仓库） | "这个项目用 Gradle"、"生产配置路径"、"当前在做的需求" | 项目目录 `.codepal/memory/` |

项目级记忆随代码库版本化（可以 git commit），团队成员共享；用户级记忆跟着人走，换项目也生效。

---

## 2. 整体架构：提取 → 存储 → 召回 → 注入

```
每轮对话结束后：
  最近 40 条消息
       ↓
  LLM 提取器（extract）
       ↓ 判断有没有值得记忆的内容
  Memory 文件（.md 格式）+ MEMORY.md 索引
       ↓
  下次新对话开始时：
  MEMORY.md 索引（轻量，作为目录）
       ↓ 注入到 System Prompt
  LLM 看到记忆索引，知道有哪些记忆存在
```

三个关键动作：
1. **提取**：每轮对话后，独立 LLM 自动从最近消息里提取值得保存的信息
2. **存储**：每条记忆是一个独立 `.md` 文件，MEMORY.md 是目录索引
3. **注入**：新对话开始时把 MEMORY.md 索引注入 System Prompt，LLM 随时可以读具体记忆文件

---

## 3. 记忆提取：LLM 自动提取，去重写入

### 3.1 什么时候触发提取

```java
// MemoryManager.java
private static final int EXTRACTION_INTERVAL = 1;  // 每轮都触发

public boolean shouldExtract() {
    turnCount++;
    return turnCount % EXTRACTION_INTERVAL == 0;
}
```

目前设置为每轮对话后都尝试提取。实际上只取最近 40 条消息，LLM 会判断有没有值得记忆的内容，没有的话输出 `NONE` 直接跳过。

### 3.2 提取时怎么防止重复写入

提取前先扫描已有记忆文件，生成一份 manifest 传给 LLM：

```java
// MemoryManager.java — scanExistingMemories()
String manifest = scanExistingMemories();
// manifest 内容类似：
// - [feedback] feedback-no-comments.md: 用户不希望代码里加多余注释
// - [project] project-build-tool.md: 项目使用 Gradle 构建
// - [user] user-profile.md: 用户是 Java 后端工程师
```

传给 LLM 的 Prompt 里明确说明：

> "Check this list before creating — update an existing file rather than creating a duplicate."

LLM 看到已有记忆后，会判断是更新已有条目还是新建。这是**LLM 驱动的去重**，不是硬编码的字符串比较。

### 3.3 提取输出格式

```
MEMORY_NAME: feedback-no-comments
MEMORY_TYPE: feedback
MEMORY_DESC: 用户不希望代码里加多余注释
MEMORY_BODY: 用户明确表示不要在代码里加注释，包括 JavaDoc 和行内注释，除非是非常复杂的算法。
---
MEMORY_NAME: project-build-tool
MEMORY_TYPE: project
MEMORY_DESC: 项目使用 Gradle 构建
MEMORY_BODY: 项目根目录有 build.gradle，使用 Gradle 构建，不要生成 pom.xml 或使用 Maven 命令。
---
```

代码解析这个输出，按 `---` 分块，提取每条记忆写成独立的 `.md` 文件。

### 3.4 记忆文件格式

```markdown
---
name: feedback-no-comments
description: 用户不希望代码里加多余注释
metadata:
  type: feedback
---

用户明确表示不要在代码里加注释，包括 JavaDoc 和行内注释，
除非是非常复杂的算法逻辑。

**Why:** 用户认为好的命名比注释更清晰，注释容易过时变成噪音。
**How to apply:** 写代码时不加注释，如果被问到为什么不加，解释这是用户偏好。
```

MEMORY.md 索引里只有一行指针：

```markdown
- [feedback-no-comments](feedback-no-comments.md) — 用户不希望代码里加多余注释
```

---

## 4. 记忆注入：MEMORY.md 作为轻量索引

### 4.1 为什么不直接把所有记忆内容全部注入

所有记忆内容全注入会占用大量 Token，而且很多记忆在当前任务里根本用不到。

设计思路：**MEMORY.md 是目录，具体文件是内容**。

- 每次注入的是 MEMORY.md 索引（每条只有一行，总共可能几百 Token）
- LLM 看到索引后知道有哪些记忆存在，需要时可以用 ReadFile 工具读具体文件
- System Prompt 里的指令告诉 LLM 什么时候应该去读记忆

### 4.2 注入的内容

```java
// MemoryManager.java — buildSystemReminder()
public String buildSystemReminder() {
    var sb = new StringBuilder();
    sb.append("# auto memory\n\n");

    // 用户级 MEMORY.md
    sb.append("## User-level MEMORY.md (`~/.codepal/memory/MEMORY.md`)\n\n");
    sb.append(/* 读取用户级 MEMORY.md 内容 */);

    // 项目级 MEMORY.md
    sb.append("## Project-level MEMORY.md (`.codepal/memory/MEMORY.md`)\n\n");
    sb.append(/* 读取项目级 MEMORY.md 内容 */);

    return sb.toString();
}
```

LLM 在每轮对话开始时都能看到类似这样的 system reminder：

```
# auto memory

## User-level MEMORY.md
- [user-profile](user-profile.md) — Java 后端工程师，目标大厂面试
- [feedback-no-comments](feedback-no-comments.md) — 用户不希望代码里加多余注释

## Project-level MEMORY.md
- [project-build-tool](project-build-tool.md) — 项目使用 Gradle 构建
- [project-prod-config](project-prod-config.md) — 生产配置路径 /prod/config.yaml
```

---

## 5. 四种记忆类型

| 类型 | 存储位置 | 内容 | 举例 |
|------|---------|------|------|
| `user` | 用户级 | 用户的角色、技能、背景 | "Java 后端工程师，熟悉 Spring Boot" |
| `feedback` | 用户级 | 用户的偏好和反馈 | "不要加注释"、"提交信息用中文" |
| `project` | 项目级 | 项目进行中的上下文 | "当前在做登录模块重构" |
| `reference` | 项目级 | 外部资源指针 | "Bug 追踪在 Linear 项目 AUTH" |

---

## 6. 面试怎么答

### 标准描述（30 秒版）

> "每次新对话 LLM 完全失忆，用户需要反复告知项目背景与个人偏好，重复劳动严重，而且容易因为'忘记说'导致 Agent 做出错误操作。
>
> 我设计了用户级和项目级两层存储：用户偏好和反馈跨项目通用，存用户目录；项目上下文随仓库版本化，存项目目录。每轮对话后独立 LLM 自动从最近 40 条消息提取记忆，提取前扫描已有记忆 manifest 做去重，新条目写成独立 .md 文件并更新 MEMORY.md 索引。新对话开始时把轻量的 MEMORY.md 索引注入 System Prompt，LLM 按需读取具体记忆文件。
>
> 用户偏好与项目上下文跨会话持续生效，重复告知次数显著减少。"

---

## 7. 大厂面试官高频追问（全集）

---

### 【设计决策类】

**Q1：为什么用独立 .md 文件存每条记忆，而不是一个大 JSON 文件？**

> 每条记忆是独立文件有几个好处：
>
> 第一，**LLM 可以直接用 ReadFile 工具读取某条记忆**——文件路径就是地址，不需要解析 JSON、定位某个字段，对 LLM 更自然。
>
> 第二，**可以用 git 版本化**——每条记忆的新增、修改、删除都是一次文件操作，git diff 可以清楚看到记忆怎么变化的，可审计。
>
> 第三，**读写粒度更精细**——更新一条记忆只写一个小文件，不需要读取然后写回整个大 JSON，减少并发冲突的可能性。

**Q2：为什么用 LLM 来做记忆提取，而不是规则匹配或关键词提取？**

> 规则匹配和关键词提取只能处理固定模式的信息，比如"用户说了 Gradle"就记"构建工具=Gradle"。但很多值得记忆的信息是隐含在对话语义里的：
>
> - 用户说"这个改法太复杂了，能简单一点吗"→ 记忆：用户偏好简单实现，不要过度设计
> - 用户说"上次那个 bug 是因为没加空判断"→ 记忆：项目里有个空指针问题的历史背景
>
> 这类语义理解只有 LLM 能做，规则做不到。代价是每轮对话要多一次 LLM 调用（提取调用），但这个调用是异步的、低优先级的，不影响主对话流程。

**Q3：记忆提取是同步还是异步的？会不会拖慢对话响应？**

> 提取是在每轮 Agent Loop 结束后调用的，时机是"Agent 完成了当前任务、等待用户下一条指令"的间隙。对用户来说，他们在看 Agent 的输出结果，提取在这个时候后台默默跑，不占用主对话的响应时间。
>
> 从代码结构看，`extract()` 是一次同步的 LLM 调用，但它发生在 Agent 完成工作之后，不在关键路径上，用户感知不到延迟。

**Q4：记忆会不会越积越多，最终把 MEMORY.md 撑得很大？**

> 这是真实的工程问题。缓解手段有两个：
>
> 第一，提取时传入已有记忆 manifest，LLM 会判断是更新已有条目还是新建，避免内容高度重复的记忆被反复创建。
>
> 第二，记忆文件本身是 `.md` 文件，用户或 Agent 可以手动删除过时的记忆（比如"当前在做登录模块"这种项目记忆，登录模块做完就可以删了）。
>
> 更完整的解决方案是定期整理（autoDream 机制）——后台定期调用 LLM 扫描所有记忆，合并重复条目、删除过时条目、更新失效信息。这是改进方向，当前版本的基础能力已经够用。

---

### 【工程化细节类】

**Q5：记忆提取的 LLM 调用和主对话用的是同一个模型吗？有没有优化成本的考虑？**

> 代码里用的是同一个 `LlmClient`，也就是同一个模型。从成本优化角度，提取任务其实不需要最强的模型——提取记忆是结构化信息抽取，用小模型（如 Haiku）就够了，比用 Sonnet/Opus 便宜得多。
>
> 这是一个明确的改进点：把提取调用指向一个更便宜的模型，主对话保留最强模型。当前版本没有实现模型路由，是因为框架里 LlmClient 是统一的抽象，还没有加"按任务类型选模型"的能力。

**Q6：提取输出的解析，如果 LLM 输出格式不规范（比如漏了 `---` 分隔符），怎么处理？**

> 代码里用 `---` 分块，然后逐块提取 `MEMORY_NAME`、`MEMORY_TYPE`、`MEMORY_DESC`、`MEMORY_BODY` 四个字段。如果某个字段缺失，`extractField()` 返回空字符串，代码会跳过 name 或 body 为空的块：
>
> ```java
> if (name.isEmpty() || body.isEmpty()) continue;
> ```
>
> 格式不规范的输出会被静默跳过，不会报错崩溃。这是"容错优先"的设计——记忆提取失败的代价很低（这轮没记住，下轮再试），不应该因为格式问题影响主流程。

**Q7：MEMORY.md 索引文件超过 200 行会被截断，这是什么机制？**

> 这是 System Prompt 的 Token 预算控制——MEMORY.md 作为 system reminder 注入，如果它太大会占用过多 Token。200 行的限制是一个经验值上限，超过这个数量的记忆条目可能会在注入时被截断。
>
> 这和记忆越积越多的问题是同一个问题的两面。解决方案同样是定期整理 + autoDream 机制，把低价值的记忆及时清理，保持 MEMORY.md 的精简。

**Q8：project 类型记忆存在 `.codepal/memory/` 里，会不会被 git commit 进仓库暴露敏感信息？**

> 这是一个需要用户注意的问题。`.codepal/` 目录默认不在 `.gitignore` 里，如果用户 `git add .`，记忆文件就会被提交。
>
> 解决方案：用户应该把 `.codepal/memory/` 加进 `.gitignore`（如果不想分享记忆），或者只提交 `permissions.yaml`、`skills/` 等需要团队共享的配置，把记忆文件留在本地。也可以用 `.codepal/memory.local/` 作为本地私有记忆目录（不提交），`.codepal/memory/` 作为团队共享记忆（提交）。这是改进方向，当前版本没有强制分离。

---

### 【量化与验证类】

**Q9："重复告知次数显著减少"怎么量化？**

> 严格量化需要用户研究——记录用户在有/没有记忆系统时需要重复告知的次数，对比均值。这在项目阶段难以做到系统性的用户研究。
>
> 替代量化方法：统计 MEMORY.md 里的记忆条目数量，以及这些记忆在后续对话中被 LLM 引用的次数（通过日志追踪 LLM 是否读取了记忆文件）。被引用的记忆条目说明这条信息如果没有记忆系统就需要用户重新告知。
>
> 这是一个"用系统行为替代用户行为"的间接量化方法，不够精确但有参考价值。

**Q10：怎么验证记忆提取的质量？提取的内容是不是真的有用？**

> 质量验证有两个维度：
>
> 1. **完整性**：值得记忆的信息有没有被提取到？方法是构造测试对话（包含明确的偏好声明、项目背景等），跑完提取后检查 MEMORY.md 里是否出现了对应条目。
>
> 2. **精准性**：提取的内容有没有误判（把不重要的话也记进去）？手动检查记忆文件的内容，判断是否符合"值得跨会话保留"的标准。
>
> 目前没有自动化的记忆质量 Eval，这是改进方向。信息保留率测试（亮点⑤评测框架）可以扩展到记忆系统——注入关键信息、触发提取、新会话检测记忆是否生效。

---

### 【对比与扩展类】

**Q11：这个记忆系统和 RAG（Retrieval-Augmented Generation）有什么区别？**

> 两者都是"给 LLM 补充外部知识"，但定位不同：
>
> | 维度 | 记忆系统 | RAG |
> |------|---------|-----|
> | 内容来源 | 从对话里自动提取的用户偏好和项目上下文 | 外部知识库（文档、代码库、数据库） |
> | 检索方式 | MEMORY.md 索引全量注入，按需读具体文件 | 向量相似度检索 |
> | 更新频率 | 每轮对话后自动更新 | 通常是静态知识库，定期批量更新 |
> | 规模 | 轻量（几十条记忆） | 可以是海量文档 |
> | 目标 | 跨会话的个性化和上下文延续 | 知识增强，弥补训练数据不足 |
>
> 两者可以组合：记忆系统管"这个用户/项目的特定信息"，RAG 管"通用领域知识"。

**Q12：如果用户的偏好发生了变化（比如从"用 Log4j"改成"用 SLF4J"），旧记忆怎么更新？**

> 提取时传入的 manifest 让 LLM 知道已有记忆的存在，LLM 会判断新信息是否和已有记忆冲突，如果冲突会生成一个同名的新记忆（覆盖写入）：
>
> ```java
> // writeMemoryFile：直接 Files.writeString 覆盖，不追加
> Files.writeString(filePath, fileContent);
> ```
>
> MEMORY.md 索引里如果已有同名文件的指针，不会重复追加（`if (!existing.contains(filename))`）。
>
> 所以更新流程是：LLM 检测到偏好变化 → 生成同名记忆文件 → 覆盖写入 → MEMORY.md 不变（指针已存在）。这是一个自然的"last-write-wins"更新机制。

---

## 8. 简历一句话（背熟）

> **跨会话记忆系统**：设计用户级与项目级两层存储，每轮对话后独立 LLM 自动从最近 40 条消息提取记忆并去重写入，MEMORY.md 索引轻量注入 System Prompt，LLM 按需读取具体记忆文件；用户偏好与项目上下文跨会话持续生效，重复告知次数显著减少。

---

*（本文档随学习对话持续更新）*
