

package com.codepal.conversation;

import java.util.Map;

public record ToolUseBlock(String toolUseId, String toolName, Map<String, Object> arguments) {}
