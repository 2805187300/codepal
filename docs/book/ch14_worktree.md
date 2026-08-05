# 第14章：Worktree — git 工作树的文件级隔离

> 目标：理解 git worktree 是什么、为什么多 Agent 并行写文件时必须用它、WorktreeManager 如何管理生命周期。
> 对应简历亮点：第6条（多 Agent 并行协作）的基础设施
> 关键文件：`worktree/WorktreeManager.java`、`worktree/AgentWorktree.java`、`worktree/SlugValidator.java`

---

## 14.1 问题：多 Agent 并发写文件会怎样？

设想 Lead Agent 同时派出三个 Teammate：
- Teammate A：修改 `src/agent/Agent.java`
- Teammate B：修改 `src/tool/ToolRegistry.java`
- Teammate C：修改 `src/compact/ContextCompactor.java`

如果它们都在同一个工作目录操作，只要 A 和 C 同时读了同一个文件，再各自写回，后写的就会覆盖先写的——**后者的修改丢失**。这是典型的并发写冲突。

更糟糕的是：A 修改到一半，B 刚好 ReadFile 读同一个文件，读到的是"修改到一半"的中间状态——**数据损坏**。

解法：每个并行 Agent 有自己独立的工作目录和文件副本，互不干扰。这正是 **git worktree** 提供的能力。

---

## 14.2 git worktree 是什么？

通常一个 git 仓库对应一个工作目录（working tree）。`git worktree` 允许你从**同一个仓库**检出多个工作目录，每个目录在不同分支上独立工作，共享同一份 `.git` 历史。

```bash
# 主目录（main 分支）
/project/                      ← git worktree（main 分支）

# 创建两个额外的 worktree
git worktree add .codepal/worktrees/agent-a001 -b agent-a001
git worktree add .codepal/worktrees/agent-b002 -b agent-b002

# 现在有三个独立目录，同一份 git 历史
/project/                          ← main
/project/.codepal/worktrees/agent-a001/   ← agent-a001 分支
/project/.codepal/worktrees/agent-b002/   ← agent-b002 分支
```

关键特性：
- 三个目录**文件完全独立**，A 修改的文件不影响 B 和主目录
- 三个目录**共享 `.git` 历史**，worktree 里的 commit 可以直接合并回主分支
- 不是复制文件（不是 `cp -r`），而是 git 的 checkout 机制，**只检出需要的文件**

---

## 14.3 WorktreeManager：生命周期管理

```java
public class WorktreeManager {

    private final String projectRoot;
    private final List<String> symlinkDirs;  // 需要符号链接的重型目录（如 node_modules）
    private final int staleCutoffHours;       // 超过多少小时认为是废弃 worktree

    private final Map<String, WorktreeInfo> worktrees = new LinkedHashMap<>();

    // 创建 worktree
    public synchronized WorktreeInfo create(String branch, Path targetDir) throws Exception {
        // 1. 校验分支名（防止路径穿越：../evil）
        SlugValidator.validate(branch);

        // 2. 目标目录默认在 .codepal/worktrees/<branch>
        Path wtDir = targetDir != null ? targetDir
                : Path.of(projectRoot, ".codepal", "worktrees", branch);

        // 3. git worktree add -B <branch> <dir>
        // -B（大写）：如果同名分支已存在（孤儿分支），重置到当前 HEAD
        runGit(projectRoot, "git", "worktree", "add", "-B", branch, wtDir.toString());

        // 4. 后置设置（settings、hooks、符号链接、.worktreeinclude）
        PostCreationSetup.perform(projectRoot, wtDir.toString(), symlinkDirs);

        worktrees.put(branch, new WorktreeInfo(wtDir.toString(), branch, Instant.now()));
        return worktrees.get(branch);
    }
}
```

**`-B` 而不是 `-b` 的原因：**

如果一个 worktree 之前被 `remove` 删掉了，它的分支可能还留着（孤儿分支）。再次创建同名 worktree 时，`-b` 会因分支已存在报错，`-B` 会把分支重置到当前 HEAD，继续创建。这让 worktree 的创建具有**幂等性**，更健壮。

**`symlinkDirs` — 重型目录符号链接：**

`node_modules`、`.gradle/caches`、`.m2/repository` 这类目录体积很大（几百 MB 到几 GB），如果每个 worktree 都复制一份，磁盘会爆。`PostCreationSetup` 会把这些目录从主目录**符号链接**过来，worktree 里看起来有这些目录，实际上共享主目录的文件，不占额外空间。

**`SlugValidator.validate(branch)`：**

```java
// worktree/SlugValidator.java（概念）
public static void validate(String branch) {
    if (branch == null || branch.isEmpty()) {
        throw new IllegalArgumentException("branch name cannot be empty");
    }
    // 防止路径穿越：不允许 ../ 或绝对路径
    if (branch.contains("..") || branch.startsWith("/") || branch.contains("\\")) {
        throw new IllegalArgumentException("invalid branch name: " + branch);
    }
    // 只允许字母、数字、-、_、.
    if (!branch.matches("[a-zA-Z0-9_.-]+")) {
        throw new IllegalArgumentException("branch name contains invalid characters: " + branch);
    }
}
```

为什么需要校验？分支名直接拼接进路径（`.codepal/worktrees/<branch>`），如果 LLM 生成了 `../../../etc/passwd` 这样的值，就会产生路径穿越攻击，让 Agent 在意想不到的目录创建文件。`SlugValidator` 是这里的安全门。

---

## 14.4 AgentWorktree：与 AgentTool 的集成接口

```java
// worktree/AgentWorktree.java（概念）
public class AgentWorktree {

    public record Result(
        String worktreePath,   // worktree 的绝对路径
        String worktreeBranch, // 分支名
        String headCommit,     // 创建时的 HEAD commit hash（用于后续 hasChanges 检测）
        String gitRoot         // git 仓库根目录
    ) {}

    // 创建 worktree（由 AgentTool.runSync 调用）
    public static Result create(String slug, String projectRoot, List<String> symlinkDirs) throws Exception {
        String branch = "agent-" + slug;
        WorktreeManager mgr = new WorktreeManager(projectRoot, symlinkDirs, 24);
        WorktreeManager.WorktreeInfo info = mgr.create(branch, null);

        // 记录创建时的 HEAD commit
        String headCommit = getHeadCommit(info.path());

        return new Result(info.path(), branch, headCommit, projectRoot);
    }

    // 构建注入给子 Agent 的 worktree 说明（让子 Agent 知道它在哪工作）
    public static String buildNotice(String mainDir, String wtPath) {
        return """
            You are working in a git worktree at: %s
            The main project is at: %s
            Make all file changes in this worktree directory.
            Do NOT modify files in the main project directory.
            """.formatted(wtPath, mainDir);
    }

    // 删除 worktree（由 AgentTool.runSync 完成后调用）
    public static void remove(String wtPath, String branch, String gitRoot) throws Exception {
        runGit(gitRoot, "git", "worktree", "remove", wtPath, "--force");
        runGit(gitRoot, "git", "branch", "-d", branch);
    }
}
```

**`WorktreeChanges.hasChanges` 的检测逻辑：**

```java
// worktree/WorktreeChanges.java（概念）
public static boolean hasChanges(String worktreePath, String headCommit) {
    // 检查两种变化：
    // 1. 未提交的修改（git status --porcelain）
    // 2. 相对于创建时 HEAD 有新 commit（git rev-parse HEAD != headCommit）
    String status = runGit(worktreePath, "git", "status", "--porcelain");
    String currentHead = runGit(worktreePath, "git", "rev-parse", "HEAD").strip();
    return !status.isBlank() || !currentHead.equals(headCommit);
}
```

---

## 14.5 stale worktree 清理

```java
public synchronized int cleanupStale(int cutoffHours) {
    Instant cutoff = Instant.now().minusSeconds((long) cutoffHours * 3600);
    int removed = 0;

    var it = worktrees.entrySet().iterator();
    while (it.hasNext()) {
        var entry = it.next();
        if (entry.getValue().createdAt().isBefore(cutoff)) {
            try {
                runGit(projectRoot, "git", "worktree", "remove", entry.getValue().path(), "--force");
                it.remove();
                removed++;
            } catch (Exception ignored) {}  // best-effort，失败跳过
        }
    }
    return removed;
}
```

Agent 崩溃或 worktree 没有正常清理时，会留下"僵尸 worktree"。定期调用 `cleanupStale`（比如每次启动时），删掉超过 24 小时的旧 worktree，防止磁盘积累垃圾。

---

## 14.6 面试官可能问的问题

**Q：多 Agent 并行操作文件时怎么避免冲突？**

> 用 git worktree：每个并行 Agent 在独立的 worktree（独立目录、独立分支）里操作文件，文件完全隔离，不会互相覆盖。重型目录（node_modules 等）通过符号链接共享，不复制。子 Agent 完成后，如果有文件改动则保留 worktree 让主 Agent 或用户决定如何合并；如果没有改动（比如只做了分析）则自动清理。分支名经过严格校验，防止路径穿越。

---

*下一章：Agent Teams — Lead-Teammate 多 Agent 并行协作*
