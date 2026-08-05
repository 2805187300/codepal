# 第11章：Skill 系统 — 可复用的任务模板

> 目标：理解 Skill 是什么、怎么定义、两种执行模式的区别，以及热加载是如何实现的。
> 关键文件：`skill/SkillCatalog.java`、`skill/SkillExecutor.java`

---

## 11.1 Skill 是什么？为什么需要它？

想象你有一个反复执行的任务：每次有新 PR，你都要让 Agent 做 code review——检查安全漏洞、性能问题、代码风格、是否有测试。

没有 Skill 时，你每次都要手动输入这一大段要求，或者写成一个 prompt 文件，每次复制粘贴。

**Skill 就是这个 prompt 的"可复用封装"**，加上了：
- **工具过滤**：code review 只需要 ReadFile/Grep，不需要 EditFile/Bash，可以限制 Agent 的工具访问范围
- **执行模式**：inline 模式把 prompt 注入当前对话；fork 模式开一个独立子 Agent，不污染当前上下文
- **热加载**：修改 Skill 文件不需要重启 Agent，下次调用自动生效
- **参数替换**：Skill 的 prompt 里可以有占位符 `$ARGUMENTS`，用户调用时传参

---

## 11.2 Skill 的文件格式

一个 Skill 是一个目录，里面有两种组织方式：

**方式一：`SKILL.md`（单文件，带 YAML frontmatter）**

```markdown
---
name: code-review
description: Review code changes for security, performance, and style
mode: fork
fork_context: none
model: claude-opus-4-8
---

You are a code reviewer. Review the current git diff for:

1. Security vulnerabilities (SQL injection, XSS, command injection)
2. Performance issues (N+1 queries, unnecessary allocations)
3. Code style (naming, documentation, dead code)
4. Test coverage (are critical paths tested?)

$ARGUMENTS

Return a structured review with severity levels: CRITICAL, WARNING, INFO.
```

**方式二：`skill.yaml` + `prompt.md`（分离格式）**

```yaml
# skill.yaml
name: code-review
description: Review code changes
mode: fork
model: claude-opus-4-8
```

```markdown
# prompt.md
You are a code reviewer...
$ARGUMENTS
```

两种格式功能等价，单文件更简洁，分离格式更清晰（适合 prompt 很长的情况）。

---

## 11.3 SkillMeta：Skill 的元数据

```java
public record SkillMeta(
    String name,         // Skill 名，用于 /skill-name 触发
    String description,  // 描述
    String whenToUse,    // 何时使用（显示在 /skills 列表里）
    List<String> tags,   // 标签，用于搜索
    String mode,         // "inline" 或 "fork"
    String model,        // 指定使用的模型（可选）
    String forkContext   // fork 时继承多少父上下文："none"/"recent"/"full"
) {}
```

**`mode` 的两种取值：**

| 模式 | 含义 | 适用场景 |
|------|------|---------|
| `inline` | 把 Skill prompt 注入当前对话 | 需要访问当前对话上下文的 Skill |
| `fork` | 开一个独立子 Agent | 独立任务，不希望污染主对话 |

**`forkContext` 决定 fork 时继承多少父上下文：**

| 值 | 含义 |
|----|------|
| `none` | 子 Agent 从空白开始（默认） |
| `recent` | 继承父对话最近 5 条消息 |
| `full` | 继承父对话全部历史 |

---

## 11.4 三层 Skill 加载（优先级覆盖）

```java
public static SkillCatalog loadCatalog(String workDir) {
    SkillCatalog c = new SkillCatalog();

    // Tier 1：内置 Skill（打包在 jar 里，优先级最低）
    for (var skill : BuiltinSkills.load()) {
        c.register(skill, "builtin");
    }

    // Tier 2：用户全局（~/.codepal/skills/，优先级中）
    String home = System.getProperty("user.home");
    c.loadTier(Path.of(home, ".codepal", "skills"), "user");

    // Tier 3：项目级（.codepal/skills/，优先级最高）
    c.loadTier(Path.of(workDir, ".codepal", "skills"), "project");

    return c;
}
```

同名 Skill，后加载的覆盖先加载的。这意味着：
- 项目级 Skill 可以覆盖用户全局 Skill
- 用户全局 Skill 可以覆盖内置 Skill

**为什么这个顺序是合理的？**

"最具体的覆盖最通用的"是一个通用的优先级原则（就像 CSS 特异性）。项目里的 code-review Skill 针对这个项目的技术栈定制，比通用版本更准确，自然应该优先。

---

## 11.5 热加载：修改 Skill 文件不需要重启

```java
// getFull：每次调用都重新从磁盘读取 Skill 内容（Phase 2 读取）
public Optional<Skill> getFull(String name) {
    Skill skill = skills.get(name);
    if (skill == null) return Optional.empty();
    if (skill.sourceDir() == null) return Optional.of(skill);  // 内置 Skill 不需要重读

    try {
        Skill reloaded = loadSkill(skill.sourceDir());  // 重新读磁盘
        if (reloaded != null) {
            skills.put(name, reloaded);  // 更新缓存
            return Optional.of(reloaded);
        }
    } catch (IOException ignored) {
        // 读取失败，使用缓存版本
    }
    return Optional.of(skill);
}
```

**两阶段加载**：

- **Phase 1**（启动时）：只读 frontmatter（name/description/mode 等元数据），不读 prompt body。这样即使有几十个 Skill，启动也很快。
- **Phase 2**（调用时）：`getFull()` 触发完整读取，包括 prompt body。而且**每次调用都重读磁盘**，不缓存 body——这就是热加载的关键。

**目录变化检测**（新增/删除 Skill 时）：

```java
public boolean needsReload() {
    for (var entry : dirModTimes.entrySet()) {
        Path dir = Path.of(entry.getKey());
        FileTime recorded = entry.getValue();
        FileTime current = Files.getLastModifiedTime(dir);
        if (!current.equals(recorded)) return true;  // 目录 mtime 变了
    }
    return false;
}
```

目录里新增一个 Skill 目录，或删除一个 Skill 目录，目录本身的 mtime（最后修改时间）会变化。`needsReload()` 检测这个变化，告诉 TUI 需要重新调用 `loadCatalog()`。

用户执行 `/skills reload` 时，TUI 调用 `catalog.reload(workDir)`，重新扫描所有 Skill。

---

## 11.6 SkillExecutor：两种执行模式的实现

### Inline 模式

```java
public static String executeInline(Skill skill, String args, SkillHost host) {
    // 1. 参数替换：把 $ARGUMENTS 替换为实际传入的参数
    String body = substituteArguments(skill.promptBody(), args);

    // 2. 激活 Skill：把 prompt 注入当前对话的 system prompt 区域
    host.activateSkill(skill.meta().name(), body);

    // 3. 记录快照（用于上下文压缩后恢复）
    host.recordSkillInvocation(skill.meta().name(), body);

    return body;
}

static String substituteArguments(String body, String args) {
    if (args == null || args.isBlank()) return body;
    if (body.contains("$ARGUMENTS")) {
        return body.replace("$ARGUMENTS", args);  // 精确替换占位符
    }
    // 没有占位符：把参数追加到 prompt 末尾
    return body + "\n\n## User Request\n\n" + args;
}
```

**`activateSkill` 做什么**：把 Skill 的 prompt 作为新章节注入到 System Prompt（priority 90），让 LLM 在当前对话中遵循这份"任务手册"。

### Fork 模式

```java
public static String executeFork(Skill skill, String args, SkillForkHost host) {
    String body = substituteArguments(skill.promptBody(), args);
    host.recordSkillInvocation(skill.meta().name(), skill.promptBody());

    // 根据 forkContext 决定传多少父上下文给子 Agent
    List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());

    // 启动独立子 Agent，阻塞等待完成，返回最终输出
    return host.runSubAgent(body, seed, skill.meta().model());
}

static List<Message> buildForkSeed(String mode, List<Message> parent) {
    return switch (mode != null ? mode : "none") {
        case "full"   -> new ArrayList<>(parent);          // 全量继承
        case "recent" -> parent.subList(                   // 只继承最近 5 条
                             Math.max(0, parent.size() - FORK_RECENT_COUNT),
                             parent.size());
        default       -> List.of();                        // 空白开始
    };
}
```

**Fork 模式的核心优势**：子 Agent 独立运行，有自己的 `ConversationManager`，运行时不影响主对话的 token 用量，出错也不污染主对话历史。

`host.runSubAgent()` 阻塞直到子 Agent 完成，返回它的最终文字输出，主 Agent 把这个输出作为工具结果注入历史。

---

## 11.7 Skill vs System Prompt vs MCP

这三者经常被混淆，一起梳理清楚：

| 对比维度 | System Prompt | Skill | MCP 工具 |
|---------|--------------|-------|---------|
| 作用层面 | 基础行为规范 | 特定任务指导 | 扩展工具能力 |
| 什么时候生效 | 全程有效 | 按需激活 | 按需加载 |
| 能添加工具？ | 否 | 否（只能过滤） | 是 |
| Token 代价 | 每轮必发 | 激活后每轮发 | 未发现时极低 |
| 定制粒度 | 全局 | 单任务 | 单工具 |

**Skill 和直接修改 System Prompt 的区别**：System Prompt 是全局固定的，对所有任务生效。Skill 是按需激活的，任务结束可以卸载。把所有场景的指令都塞进 System Prompt，token 浪费，而且不同任务的指令互相干扰。

---

## 11.8 面试官可能问的问题

**Q1：Skill 系统是怎么设计的？inline 和 fork 模式有什么区别？**

> Skill 是 Markdown 文件定义的可复用 prompt 模板，声明名字、描述、执行模式和可选工具过滤。三层加载：内置→用户全局→项目级，后者覆盖前者。两种执行模式：inline 把 prompt 注入当前对话的 System Prompt，LLM 带着这份"任务手册"继续工作；fork 开独立子 Agent，用 Skill prompt 作为 system 指令，子 Agent 有自己的对话历史，不影响主对话。热加载靠两阶段读取实现：启动时只读 frontmatter，调用时每次重读磁盘，文件改了下次调用自动生效。

**Q2：fork 模式的子 Agent、第13章的 SubAgent、第15章的 Teams，三个都涉及"子 Agent"，什么关系？**

> 这三者是**同一套子 Agent 机制在不同封装层的体现**，容易混淆，要理清：
>
> | | 触发方 | 生命周期 | 通信 | 典型场景 |
> |---|--------|---------|------|---------|
> | **Skill fork**（本章）| 用户敲 `/skill-name` | 一次性，执行完就退 | 无，返回最终文字 | 用户主动调用的独立任务（如 code review）|
> | **SubAgent**（第13章）| LLM 调 AgentTool | 一次性（sync 阻塞 / async 后台）| 无，返回最终结果 | LLM 自己决定"这块交给子 Agent 做" |
> | **Teammate**（第15章）| Lead 派发 | 长期运行，可持续接任务 | FileMailBox 双向通信 | 大型并行协作 |
>
> 底层都复用同一个 Agent 类和 fork 机制（`buildForkSeed`/`buildForkedConversation`）。区别在于：**谁触发**（用户 / LLM / Lead）、**活多久**（一次性 / 常驻）、**要不要通信**（单向返回 / 双向消息）。Skill fork 是最轻量的一次性封装，Teammate 是最重的常驻协作封装。

**Q3：Skill 的工具过滤是怎么实现的？和第4章 Agent Loop 里的 toolNameFilter 是什么关系？**

> Skill 可以声明"这个任务只允许用哪些工具"（比如 code-review 只给 ReadFile/Grep，不给 EditFile/Bash，防止 review 过程里误改代码）。实现上就是**第4章 Agent Loop 第4步提到的 `toolNameFilter`**——构建发给 LLM 的工具列表时，用这个过滤器筛掉不允许的工具，LLM 从一开始就"看不到"被禁的工具，自然不会调用。
>
> 这是"能力约束"的一种：不是等 LLM 调了危险工具再拦（那是第6章权限系统做的事），而是**从工具列表里直接删掉**，让 LLM 无从选择。两者互补——工具过滤是"事前不给看"，权限系统是"事中拦截"。

**Q4：热加载为什么用 mtime 检测（`needsReload`），而不用文件监听（Java WatchService）？**

> 权衡结果。WatchService 能实时监听文件变化、无需轮询，看起来更"高级"，但它有几个坑：跨平台行为不一致（macOS 的 WatchService 基于轮询、延迟高且不可靠）、需要额外线程常驻监听、对符号链接和网络文件系统支持差。而 Skill 的调用频率很低（用户不会每秒敲 `/skill`），**在调用时顺手比对一次目录 mtime** 成本可忽略，实现简单、跨平台一致、无常驻线程。这是典型的"低频操作不值得上重型方案"的工程判断——不是 WatchService 不好，是这个场景用不着。

---

## 11.9 生产中可能遇到的问题

**问题1：热加载读到"写了一半"的 Skill 文件**

用户正在编辑器里改 `SKILL.md`，还没保存完（或编辑器分两次写：先清空再写入），此时 Agent 恰好调用 `getFull()` 重读磁盘，可能读到空文件或半截 YAML frontmatter，导致解析失败。

**处理策略**：`getFull()` 里 `loadSkill()` 抛 `IOException` 时**回退到缓存版本**（代码里的 `catch (IOException ignored)` 就是干这个），保证不会因为一次读取失败就让 Skill 不可用。更严格的做法是校验 frontmatter 完整性（name/description 必须存在），不完整就用旧版本。

**问题2：`forkContext: full` 导致子 Agent 也上下文超长**

如果主对话已经很长（比如已经 15 万 token），Skill 声明 `fork_context: full` 全量继承，子 Agent 一启动上下文就爆了，第一轮 LLM 调用直接 400。

**处理策略**：`full` 模式要慎用，只在子任务确实需要完整上下文时才配。更安全的默认是 `none` 或 `recent`。框架层可以加保护：fork 时如果 seed 消息的估算 token 超过阈值，自动降级为 `recent` 或触发一次压缩再 fork。

**问题3：工具过滤把必需工具也过滤掉，Skill 无法完成任务**

用户定义了一个 code-review Skill，工具过滤只留了 ReadFile，但 review 时需要 `git diff`（要 Bash 或专门的 git 工具）。结果 LLM 想看 diff 却发现没有能用的工具，只能干瞪眼或者胡编。

**处理策略**：工具过滤是"最小权限"，但过度限制会让 Skill 瘫痪。设计 Skill 时要想清楚任务的**完整工具依赖链**。框架可以在 Skill 激活时校验——如果 prompt 里提到的操作（如"查看 git diff"）对应的工具被过滤了，给用户一个警告。

**问题4：fork 子 Agent 失败/超时如何影响主对话**

fork 模式下 `host.runSubAgent()` 是**阻塞**的。如果子 Agent 陷入长任务或死循环（虽有 maxIterations 保护但仍可能跑很久），主对话会一直卡住，用户以为框架挂了。

**处理策略**：给 fork 子 Agent 加超时和取消机制（用户可 Ctrl+C 中断），子 Agent 失败时返回一个明确的错误结果（而不是抛异常炸掉主对话），让主 Agent 知道"这个 Skill 没跑成"并能继续。

**问题5（用户视角）：三层覆盖时用户不知道实际生效的是哪一层**

用户在项目里定义了 code-review，但内置也有一个 code-review，用户改了内置的（找不到文件）或改了用户全局的（被项目级覆盖了），改完发现"怎么没生效"。

**处理策略**：`/skills` 列表应显示每个 Skill 的**来源层级**（builtin / user / project），让用户一眼看出实际生效的是哪个文件。这是三层覆盖机制必须配套的可观测性——覆盖规则再合理，用户看不到也会困惑。

**问题6（用户视角）：Skill 改了但用户以为要重启**

热加载是隐式的（下次调用自动重读），但用户不一定知道。他改完 Skill 可能习惯性重启整个 Agent，或者反过来——改完不知道已经生效、还在等"重新加载"。

**处理策略**：文档里明确说明"改完直接再调用即可，无需重启"；`/skills reload` 命令给用户一个**显式**的、有反馈的重载入口（"reloaded 5 skills"），照顾那些不确定热加载是否生效的用户。

---

*下一章：Hook 系统 — 生命周期钩子与自动化扩展*
