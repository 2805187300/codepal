# 模块五：五层权限安全隔离

> 对应简历亮点：第 4 条 — 五层安全隔离，权限弹窗从 30 次降至 5 次以内
> 关键文件：`permission/PermissionChecker.java`、`permission/PermissionMode.java`

---

## 1. 问题背景

Agent 全自动模式下，每次写文件、执行命令都弹窗确认，一个复杂任务会弹 30 次，严重打断工作流。但完全不弹窗又有安全风险（LLM 可能跑偏执行破坏性操作）。目标：**最小弹窗次数，同时零越权事故**。

---

## 2. 四种运行模式

```java
public enum PermissionMode {
    DEFAULT,       // READ 自动放行，WRITE/COMMAND 弹窗
    ACCEPT_EDITS,  // READ+WRITE 自动放行，COMMAND 弹窗
    PLAN,          // 只读，禁止修改
    BYPASS         // 全部放行（CI 场景）
}
```

---

## 3. 五层过滤链（按顺序）

```
工具调用进来
 → [Layer 0] Plan Mode：白名单 4 个工具放行，写 plan 文件放行，其余继续
 → [Layer 1] 安全命令白名单：ls/cat/grep/git status 等 + 无复合操作符 → ALLOW
 → [Layer 2] 危险命令黑名单：rm -rf /、mkfs、fork bomb、curl|bash → DENY
 → [Layer 2b] 受保护路径：config.yaml、permissions.local.yaml、skills/ → DENY
 → [Layer 3] 路径沙箱：只允许项目目录和 /tmp，其他路径 → ASK
 → [Layer 4] YAML 规则：用户自定义 allow/deny/ask 规则（last wins）
 → [Layer 4b] allow-always 记忆：用户本次会话选过"不再询问" → ALLOW
 → [Layer 4c] OS 沙箱已启用时：命令操作直接放行（内核已兜底）
 → [Layer 5] 模式矩阵兜底：READ→ALLOW，WRITE/COMMAND→ASK
```

---

## 4. 弹窗从 30 次降到 5 次的三个机制

1. **Layer 1 白名单**：覆盖最高频的只读命令，直接放行不弹窗
2. **Layer 4b allow-always**：用户选一次"以后不再询问"，整个 Session 内该命令不再弹窗
3. **ACCEPT_EDITS 模式**：写文件自动放行，只有执行命令才弹窗

---

## 5. 关键设计点

**受保护路径（Layer 2b）防权限提升**：

AI 若能修改 `permissions.local.yaml`，就能给自己授权；若能改 `config.yaml`，就能换掉 API Key。这三个路径是永久禁区，使用 `Path.startsWith`（路径组件级前缀匹配）防路径遍历。

**黑名单不是完备防护**：

`rm -rf /` 有无数变体，黑名单穷举不了。黑名单负责"快速拦最明显的"，真正的安全边界是路径沙箱（Layer 3）和 OS 沙箱（macOS seatbelt / Linux bwrap）。

**权限弹窗的异步等待**：

```java
var future = new CompletableFuture<PermissionResponse>();
putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
PermissionResponse response = future.get(5, TimeUnit.MINUTES);  // 超时自动 DENY
```

Agent 线程阻塞等待，TUI 线程弹窗渲染，用户点击后 `future.complete()`，互不持有对方引用。

---

## 6. 面试标准答法

**Q：五层权限是怎么设计的？**

> 五层顺序执行：Layer 0 处理 Plan Mode；Layer 1 白名单快速放行安全命令；Layer 2 正则拒绝明确危险命令；Layer 3 路径沙箱限制文件操作范围；Layer 4 用户 YAML 规则 + Session 级 allow-always 记忆；Layer 5 模式矩阵兜底。每层只处理自己的范围，通过的才到下一层。

**Q：你说"五层"，流程图里有更多判断点？**

> "五层"是逻辑分层（五类性质不同的防御），带字母后缀的（2b/4b/4c）是同一逻辑层内的细分判断点，不是独立层。

**Q：如何防止 LLM 修改配置文件提升权限？**

> Layer 2b 的受保护路径机制，`config.yaml`/`permissions.local.yaml`/`skills/` 三个路径永久禁止写入，即使用户授权了"可以写任何文件"也无效。Path.startsWith 用路径组件级匹配，防路径遍历绕过。

---

*下一模块：可观测性体系 + 评测驱动优化*
