
package com.codepal.tool.impl;

import com.codepal.skill.SkillCatalog;
import com.codepal.tool.Tool;
import com.codepal.tool.ToolCategory;
import com.codepal.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 按名称激活 Skill，将完整 SOP 注入对话上下文。
 * <p>
 * 系统提示词里只列出 Skill 的名称和一句话描述（渐进式披露），
 * 模型根据用户意图调用本工具加载完整指令。
 */
public class LoadSkillTool implements Tool {

    private static final String DESCRIPTION =
            "Activate a Skill by name. Returns the full SOP body so you can follow its "
            + "instructions. Call this when the user's request matches one of the available "
            + "Skills listed in the system prompt. Pass the Skill name without a leading slash.";

    private SkillCatalog catalog;
    // (name, body) → 注入对话上下文
    private BiConsumer<String, String> onActivate;

    public void setCatalog(SkillCatalog catalog) { this.catalog = catalog; }

    public void setOnActivate(BiConsumer<String, String> callback) { this.onActivate = callback; }

    @Override
    public String name() { return "LoadSkill"; }

    @Override
    public String description() { return DESCRIPTION; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description",
                                        "The Skill name to activate (e.g. \"commit\", \"backend-interview\")."
                                )
                        ),
                        "required", List.of("name")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String name = args.getOrDefault("name", "").toString();
        if (name.isEmpty()) {
            return ToolResult.error("name is required");
        }
        if (catalog == null) {
            return ToolResult.error("LoadSkill not initialized (catalog is null)");
        }

        var skill = catalog.getFull(name);
        if (skill.isEmpty()) {
            return ToolResult.error("unknown skill: " + name);
        }

        String body = skill.get().promptBody();
        if (body == null || body.isEmpty()) {
            return ToolResult.error("skill \"" + name + "\" has empty body — cannot activate");
        }

        if (onActivate != null) {
            onActivate.accept(name, body);
        }

        return ToolResult.success("# Skill: " + name + "\n\n" + body);
    }
}
