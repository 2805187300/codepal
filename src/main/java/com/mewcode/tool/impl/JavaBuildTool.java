package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class JavaBuildTool implements Tool {

    private static final int TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_LENGTH = 8000;

    private static final String DESCRIPTION = """
            Compile a Java/Maven/Gradle project and return structured output.

            Detects build system automatically:
            - If pom.xml exists: runs mvn compile (or mvn test-compile)
            - If build.gradle or build.gradle.kts exists: runs ./gradlew compileJava (or gradlew.bat on Windows)
            - Falls back to javac for single files

            Parameters:
            - project_path (string, required): path to project root containing pom.xml or build.gradle
            - goal (string, optional, default "compile"): "compile" | "test-compile" | "package" | "install"
            - args (string, optional): extra CLI args passed through

            Returns structured output:
            - BUILD SUCCESS / BUILD FAILURE status line
            - Parsed compiler errors: each as "FILE:LINE: ERROR: message"
            - Summary: X errors, Y warnings
            """;

    @Override
    public String name() {
        return "JavaBuild";
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.COMMAND;
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
                                        "description", "Path to project root containing pom.xml or build.gradle"
                                ),
                                "goal", Map.of(
                                        "type", "string",
                                        "description", "Build goal: compile | test-compile | package | install",
                                        "default", "compile"
                                ),
                                "args", Map.of(
                                        "type", "string",
                                        "description", "Extra CLI args passed through to the build tool"
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

        String goal = parameters.containsKey("goal") ? (String) parameters.get("goal") : "compile";
        if (goal == null || goal.isBlank()) {
            goal = "compile";
        }

        String extraArgs = parameters.containsKey("args") ? (String) parameters.get("args") : null;

        Path workDir = Paths.get(projectPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(workDir)) {
            return ToolResult.error("project_path does not exist or is not a directory: " + workDir);
        }

        List<String> command = buildCommand(workDir, goal, extraArgs);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder outputBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputBuilder.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Build timed out after " + TIMEOUT_SECONDS + " seconds");
            }

            int exitCode = process.exitValue();
            String rawOutput = outputBuilder.toString();

            int errorCount = countMatches(rawOutput, "ERROR", "error:");
            int warningCount = countMatches(rawOutput, "WARNING", "warning:");

            String summary = "Summary: " + errorCount + " error(s), " + warningCount + " warning(s)\n";
            String truncated = truncate(rawOutput, MAX_OUTPUT_LENGTH);

            if (exitCode == 0) {
                return ToolResult.success("BUILD SUCCESS\n" + summary + truncated);
            } else {
                return ToolResult.error("BUILD FAILED\n" + summary + truncated);
            }

        } catch (Exception e) {
            return ToolResult.error("Failed to execute build: " + e.getMessage());
        }
    }

    private List<String> buildCommand(Path workDir, String goal, String extraArgs) {
        List<String> command = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        if (Files.exists(workDir.resolve("pom.xml"))) {
            command.add(isWindows ? "mvn.cmd" : "mvn");
            command.add(mapMavenGoal(goal));
            command.add("-B");
        } else if (Files.exists(workDir.resolve("build.gradle.kts"))
                || Files.exists(workDir.resolve("build.gradle"))) {
            File gradlew = workDir.resolve(isWindows ? "gradlew.bat" : "gradlew").toFile();
            if (gradlew.exists()) {
                command.add(gradlew.getAbsolutePath());
            } else {
                command.add("gradle");
            }
            command.add(mapGradleTask(goal));
            command.add("--console=plain");
        } else {
            command.add(isWindows ? "javac.exe" : "javac");
            command.add("-version");
        }

        if (extraArgs != null && !extraArgs.isBlank()) {
            for (String part : extraArgs.trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    command.add(part);
                }
            }
        }

        return command;
    }

    private String mapMavenGoal(String goal) {
        return switch (goal) {
            case "test-compile" -> "test-compile";
            case "package" -> "package";
            case "install" -> "install";
            default -> "compile";
        };
    }

    private String mapGradleTask(String goal) {
        return switch (goal) {
            case "test-compile" -> "compileTestJava";
            case "package", "install" -> "build";
            default -> "compileJava";
        };
    }

    private int countMatches(String output, String... patterns) {
        int count = 0;
        for (String line : output.split("\n")) {
            for (String pattern : patterns) {
                if (line.contains(pattern)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "\n... (output truncated)";
    }
}
