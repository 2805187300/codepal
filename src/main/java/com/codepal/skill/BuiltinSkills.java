
package com.codepal.skill;

import com.codepal.skill.SkillCatalog.Skill;

import java.util.*;

/**
 * 内置 skill 加载器。
 * 当前版本不包含任何内置 skill，所有 skill 通过用户目录或项目目录加载。
 */
public final class BuiltinSkills {

    private BuiltinSkills() {}

    /**
     * 返回空列表——不再内置任何 skill。
     */
    public static List<Skill> load() {
        return List.of();
    }
}
