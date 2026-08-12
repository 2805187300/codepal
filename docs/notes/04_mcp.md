# 模块四：MCP 工具延迟加载

> 对应简历亮点：第 2 条 — MCP 工具延迟加载，百级工具场景下 Token 占用减少 85%
> 关键文件：`mcp/McpManager.java`、`tool/impl/ToolSearchTool.java`、`tool/ToolRegistry.java`

---

## 1. 问题背景

接入 10 个 MCP Server、每个 10 个工具，共 100 个工具。每个工具 schema 平均 200 token，全量注入就是 20000 token/轮，而绝大多数工具当次任务根本用不到。挤占有效上下文，还多花钱。

---

## 2. 解法：两阶段工具暴露

**阶段一**：每轮只给 LLM 一个工具名字列表（~500 token），不给完整 schema。

```java
// Agent.java
var deferredNames = registry.getDeferredToolNames();
// 注入 system reminder："以下工具通过 ToolSearch 按需加载"
conv.addSystemReminder("deferred tools: " + String.join(", ", deferredNames));
```

**阶段二**：LLM 需要某个工具时，主动调 `ToolSearch` 加载完整 schema，下一轮才能真正调用。

```java
// ToolRegistry
public void markDiscovered(String name) {
    discoveredTools.add(name);  // 打标记
}
public List<Map<String, Object>> getAllSchemas(String protocol) {
    // shouldDefer() && !discovered → 跳过
}
```

**为什么需要两轮？** 工具调用能力来自 API 请求里的 `tools` 字段，不来自对话历史内容。ToolSearch 把 schema 返回给 LLM 是工具结果，LLM 能"看到"，但只有**下一轮 API 调用**才会把它放进 `tools` 字段。

---

## 3. McpToolWrapper：适配器模式

```java
// MCP 工具统一包装成 Tool 接口
@Override public String name() { return "mcp__" + sanitizeName(serverName) + "__" + sanitizeName(sdkTool.name()); }
@Override public ToolCategory category() { return ToolCategory.COMMAND; }  // MCP 默认有副作用
@Override public boolean shouldDefer() { return true; }  // 默认延迟加载
```

`sanitizeName` 把 `my-github-server/create-issue` 转成 `mcp__my_github_server__create_issue`，用前缀避免不同 Server 工具名冲突。

---

## 4. ToolSearch 两种查询模式

```java
if (query.startsWith("select:")) {
    // 精确选取：select:mcp__github__create_issue,mcp__github__list_prs
} else {
    // 关键字搜索：名字 + 描述 contains 匹配
}
// 标记 discovered，下一轮生效
registry.markDiscovered(name);
```

---

## 5. Token 节省量化

| 方案 | 100 工具每轮 Token |
|------|-----------------|
| 全量注入 | 100 × 200 = 20000 |
| 延迟加载（用 3 个）| 100×8 + 3×200 = 1400 |
| 节省 | **93%**（保守估计 85%）|

---

## 6. 面试标准答法

**Q：MCP 工具延迟加载是怎么做的？**

> 每个 MCP 工具实现 `shouldDefer()=true`，不进入每轮的 `tools` 字段。Agent 只把工具名列表注入 system reminder（500 token），LLM 需要某个工具时主动调内置的 ToolSearch 加载完整 schema 并 markDiscovered，下一轮这个工具才真正出现在 `tools` 里可以被调用。百级工具场景下工具描述 token 占用减少 85%。

**Q：为什么不直接用关键词搜索而不是 select 模式？**

> 两种模式各有适用场景。LLM 已经从名字列表里识别出目标工具时，`select:` 精确加载，零误差。不确定工具名时用关键词搜索，按名字和描述模糊匹配。关键词搜索存在"搜不到"的风险（工具描述写得太简单），所以 ToolSearch 在未找到时会列出所有可用工具名，帮助 LLM 纠正查询。

**Q：延迟加载的代价是什么？**

> 两个代价要诚实说：一是多一轮往返（LLM 先调 ToolSearch，再调目标工具）；二是 LLM 可能没意识到需要先搜（对 system reminder 没反应）。对工具少的场景（10 个以内）直接全量注入更好，延迟加载是匹配"百级工具"规模的方案。

---

*下一模块：五层权限安全隔离（PermissionChecker.java）*
