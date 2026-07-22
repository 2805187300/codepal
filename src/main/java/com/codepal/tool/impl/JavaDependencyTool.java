package com.codepal.tool.impl;

import com.codepal.tool.Tool;
import com.codepal.tool.ToolCategory;
import com.codepal.tool.ToolResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class JavaDependencyTool implements Tool {

    private static final int MAX_OUTPUT_LENGTH = 6000;
    private static final int TIMEOUT_SECONDS = 120;

    private static final String DESCRIPTION = """
            Analyze Java project dependencies (Maven or Gradle) and return structured info.

            Parameters:
            - project_path (string, required): project root
            - action (string, optional, default "list"):
              - "list": list all direct dependencies with versions
              - "tree": show full dependency tree
              - "check-updates": show dependencies with newer versions available (Maven only)

            Returns structured dependency list or tree.""";

    @Override
    public String name() {
        return "JavaDependency";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "project_path", Map.of(
                                        "type", "string",
                                        "description", "Absolute path to the Java project root containing pom.xml or build.gradle"
                                ),
                                "action", Map.of(
                                        "type", "string",
                                        "description", "Action to perform: \"list\" (default), \"tree\", or \"check-updates\" (Maven only)",
                                        "enum", List.of("list", "tree", "check-updates")
                                )
                        ),
                        "required", List.of("project_path")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String projectPath = (String) parameters.get("project_path");
        if (projectPath == null || projectPath.isBlank()) {
            return ToolResult.error("Missing required parameter: project_path");
        }

        String action = (String) parameters.getOrDefault("action", "list");
        if (action == null || action.isBlank()) {
            action = "list";
        }

        Path projectRoot = Paths.get(projectPath);
        if (!Files.exists(projectRoot) || !Files.isDirectory(projectRoot)) {
            return ToolResult.error("project_path does not exist or is not a directory: " + projectPath);
        }

        BuildTool buildTool = detectBuildTool(projectRoot);
        if (buildTool == BuildTool.NONE) {
            return ToolResult.error(
                    "No build file found in project root. Expected pom.xml (Maven) or build.gradle / build.gradle.kts (Gradle) at: "
                            + projectPath);
        }

        if ("check-updates".equals(action) && buildTool == BuildTool.GRADLE) {
            return ToolResult.error("action \"check-updates\" is only supported for Maven projects.");
        }

        List<String> command = buildCommand(buildTool, action);
        if (command == null) {
            return ToolResult.error("Unsupported action '" + action + "' for build tool " + buildTool.name());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Command timed out after " + TIMEOUT_SECONDS + " seconds: "
                        + String.join(" ", command));
            }

            int exitCode = process.exitValue();
            String rawOutput = outputBuilder.toString();
            String cleanedOutput = cleanOutput(rawOutput);

            if (exitCode != 0 && cleanedOutput.isBlank()) {
                return ToolResult.error("Command failed with exit code " + exitCode + ": "
                        + String.join(" ", command));
            }

            String result = formatResult(buildTool, action, cleanedOutput, exitCode);
            return ToolResult.success(result);

        } catch (Exception e) {
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        }
    }

    private BuildTool detectBuildTool(Path projectRoot) {
        if (Files.exists(projectRoot.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"))) {
            return BuildTool.GRADLE;
        }
        return BuildTool.NONE;
    }

    private List<String> buildCommand(BuildTool buildTool, String action) {
        return switch (action) {
            case "list" -> switch (buildTool) {
                case MAVEN -> List.of("mvn", "dependency:list", "-DincludeScope=compile", "-q");
                case GRADLE -> List.of("./gradlew", "dependencies", "--configuration", "compileClasspath", "-q");
                default -> null;
            };
            case "tree" -> switch (buildTool) {
                case MAVEN -> List.of("mvn", "dependency:tree");
                case GRADLE -> List.of("./gradlew", "dependencies");
                default -> null;
            };
            case "check-updates" -> switch (buildTool) {
                case MAVEN -> List.of("mvn", "versions:display-dependency-updates", "-q");
                default -> null;
            };
            default -> null;
        };
    }

    private String cleanOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            if (isDownloadProgressLine(line)) {
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            lines.add(line);
        }

        String joined = String.join(System.lineSeparator(), lines);
        if (joined.length() > MAX_OUTPUT_LENGTH) {
            joined = joined.substring(0, MAX_OUTPUT_LENGTH)
                    + System.lineSeparator()
                    + "... [output truncated at " + MAX_OUTPUT_LENGTH + " characters]";
        }
        return joined;
    }

    private boolean isDownloadProgressLine(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.startsWith("[INFO] Downloading")
                || trimmed.startsWith("[INFO] Downloaded")
                || trimmed.startsWith("Downloading:")
                || trimmed.startsWith("Downloaded:");
    }

    private String formatResult(BuildTool buildTool, String action, String output, int exitCode) {
        StringBuilder sb = new StringBuilder();
        sb.append("Build Tool : ").append(buildTool.name()).append(System.lineSeparator());
        sb.append("Action     : ").append(action).append(System.lineSeparator());
        sb.append("Exit Code  : ").append(exitCode).append(System.lineSeparator());
        sb.append("---").append(System.lineSeparator());
        sb.append(output);
        return sb.toString();
    }

    private enum BuildTool {
        MAVEN,
        GRADLE,
        NONE
    }
}
