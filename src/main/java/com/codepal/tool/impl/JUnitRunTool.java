package com.codepal.tool.impl;

import com.codepal.tool.Tool;
import com.codepal.tool.ToolCategory;
import com.codepal.tool.ToolResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JUnitRunTool implements Tool {

    private static final int DEFAULT_TIMEOUT = 120;
    private static final int MAX_TIMEOUT = 600;

    private static final String DESCRIPTION = """
            Run JUnit tests in a Maven or Gradle project and return results.

            Detects build system automatically (pom.xml → mvn, build.gradle* → gradlew).

            Parameters:
            - project_path (string, required): project root
            - test_filter (string, optional): test class or method filter
              - Maven format: "com.example.FooTest" or "com.example.FooTest#testMethod"
              - Gradle format: "com.example.FooTest.testMethod"
            - timeout (integer, optional, default 120): seconds

            Returns:
            - Tests run: N, Failures: N, Errors: N, Skipped: N
            - Each FAILED test: class#method + failure message excerpt
            - Exit code info
            """;

    @Override
    public String name() {
        return "JUnitRun";
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
                                        "description", "Project root directory containing pom.xml or build.gradle"
                                ),
                                "test_filter", Map.of(
                                        "type", "string",
                                        "description", "Optional test class or method filter. Maven: 'com.example.FooTest' or 'com.example.FooTest#testMethod'. Gradle: 'com.example.FooTest.testMethod'"
                                ),
                                "timeout", Map.of(
                                        "type", "integer",
                                        "description", "Timeout in seconds (default 120, max 600)",
                                        "default", DEFAULT_TIMEOUT
                                )
                        ),
                        "required", List.of("project_path")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String projectPath = stringArg(args, "project_path", "");
        if (projectPath.isEmpty()) {
            return ToolResult.error("Error: project_path is required");
        }

        String testFilter = stringArg(args, "test_filter", "");
        int timeout = intArg(args, "timeout", DEFAULT_TIMEOUT);
        if (timeout <= 0) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }

        Path projectDir = Paths.get(projectPath);
        if (!Files.isDirectory(projectDir)) {
            return ToolResult.error("Error: project_path does not exist or is not a directory: " + projectPath);
        }

        BuildSystem buildSystem = detectBuildSystem(projectDir);
        if (buildSystem == BuildSystem.NONE) {
            return ToolResult.error(
                    "Error: No supported build system detected in: " + projectPath +
                    "\nExpected pom.xml (Maven) or build.gradle / build.gradle.kts (Gradle)."
            );
        }

        List<String> cmd = buildCommand(buildSystem, projectDir, testFilter);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder rawOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    rawOutput.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Error: test run timed out after " + timeout + "s");
            }

            int exitCode = process.exitValue();
            String output = rawOutput.toString();

            return formatResult(output, exitCode, buildSystem, cmd);

        } catch (IOException e) {
            return ToolResult.error("Error starting test process: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Error: test run was interrupted");
        }
    }

    // -------------------------------------------------------------------------
    // Build system detection
    // -------------------------------------------------------------------------

    private enum BuildSystem {
        MAVEN, GRADLE, NONE
    }

    private BuildSystem detectBuildSystem(Path projectDir) {
        if (Files.exists(projectDir.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        }
        if (Files.exists(projectDir.resolve("build.gradle"))
                || Files.exists(projectDir.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        return BuildSystem.NONE;
    }

    // -------------------------------------------------------------------------
    // Command construction
    // -------------------------------------------------------------------------

    private List<String> buildCommand(BuildSystem buildSystem, Path projectDir, String testFilter) {
        List<String> cmd = new ArrayList<>();

        if (buildSystem == BuildSystem.MAVEN) {
            cmd.add("mvn");
            cmd.add("test");
            if (!testFilter.isEmpty()) {
                cmd.add("-Dtest=" + testFilter);
            }
            cmd.add("-Dsurefire.failIfNoSpecifiedTests=false");
            cmd.add("-pl");
            cmd.add(".");
        } else {
            // Gradle: prefer ./gradlew, fall back to system gradle
            File gradlew = projectDir.resolve("gradlew").toFile();
            if (gradlew.exists() && gradlew.canExecute()) {
                cmd.add("./gradlew");
            } else {
                cmd.add("gradle");
            }
            cmd.add("test");
            if (!testFilter.isEmpty()) {
                cmd.add("--tests");
                cmd.add(testFilter);
            }
        }

        return cmd;
    }

    // -------------------------------------------------------------------------
    // Output parsing and formatting
    // -------------------------------------------------------------------------

    /**
     * Maven Surefire summary line looks like:
     *   Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
     *
     * Gradle summary looks like:
     *   5 tests completed, 1 failed
     *   or individual lines with > Task :test FAILED
     */
    private static final Pattern MAVEN_SUMMARY =
            Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    private static final Pattern GRADLE_SUMMARY =
            Pattern.compile("(\\d+) tests? completed(?:,\\s*(\\d+) failed)?(?:,\\s*(\\d+) skipped)?");

    /** Maven Surefire failure block start: "  com.example.FooTest  Time elapsed: ... <<< FAILURE!" */
    private static final Pattern MAVEN_FAILURE_LINE =
            Pattern.compile("(.+?)(?:\\s+Time elapsed:.+)?<<<\\s*(FAILURE|ERROR)!");

    /** Gradle test failure: "> FooTest > testMethod FAILED" */
    private static final Pattern GRADLE_FAILURE_LINE =
            Pattern.compile(">\\s+(.+?)\\s+FAILED");

    private ToolResult formatResult(String rawOutput, int exitCode, BuildSystem buildSystem, List<String> cmd) {
        StringBuilder sb = new StringBuilder();

        // Append the command that was run
        sb.append("Command: ").append(String.join(" ", cmd)).append('\n');
        sb.append('\n');

        // Parse summary statistics
        int totalRun = -1, failures = -1, errors = -1, skipped = -1;
        List<String> failedTests = new ArrayList<>();

        String[] lines = rawOutput.split("\n");

        if (buildSystem == BuildSystem.MAVEN) {
            // Accumulate totals across all module/class summary lines
            int sumRun = 0, sumFail = 0, sumErr = 0, sumSkip = 0;
            boolean foundAny = false;

            for (String line : lines) {
                Matcher m = MAVEN_SUMMARY.matcher(line);
                if (m.find()) {
                    foundAny = true;
                    sumRun  += Integer.parseInt(m.group(1));
                    sumFail += Integer.parseInt(m.group(2));
                    sumErr  += Integer.parseInt(m.group(3));
                    sumSkip += Integer.parseInt(m.group(4));
                }
                Matcher mf = MAVEN_FAILURE_LINE.matcher(line.trim());
                if (mf.find()) {
                    String testId = mf.group(1).trim();
                    if (!failedTests.contains(testId)) {
                        failedTests.add(testId);
                    }
                }
            }
            if (foundAny) {
                totalRun = sumRun;
                failures = sumFail;
                errors   = sumErr;
                skipped  = sumSkip;
            }

        } else {
            // Gradle
            for (String line : lines) {
                Matcher m = GRADLE_SUMMARY.matcher(line.trim());
                if (m.find()) {
                    totalRun = Integer.parseInt(m.group(1));
                    failures = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
                    skipped  = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
                    errors   = 0;
                }
                Matcher mf = GRADLE_FAILURE_LINE.matcher(line.trim());
                if (mf.find()) {
                    String testId = mf.group(1).trim();
                    if (!failedTests.contains(testId)) {
                        failedTests.add(testId);
                    }
                }
            }
        }

        // Summary block
        if (totalRun >= 0) {
            sb.append("=== Test Summary ===\n");
            sb.append("Tests run: ").append(totalRun);
            sb.append(", Failures: ").append(failures >= 0 ? failures : 0);
            sb.append(", Errors: ").append(errors >= 0 ? errors : 0);
            sb.append(", Skipped: ").append(skipped >= 0 ? skipped : 0);
            sb.append('\n');
        } else {
            sb.append("=== Test Summary ===\n");
            sb.append("(Could not parse test counts from output)\n");
        }

        // Build status
        boolean buildSuccess = rawOutput.contains("BUILD SUCCESS");
        boolean buildFailure = rawOutput.contains("BUILD FAILURE")
                || rawOutput.contains("BUILD FAILED");

        if (buildSuccess) {
            sb.append("Build status: SUCCESS\n");
        } else if (buildFailure) {
            sb.append("Build status: FAILURE\n");
        }

        sb.append("Exit code: ").append(exitCode).append('\n');

        // Failed test details
        if (!failedTests.isEmpty()) {
            sb.append('\n');
            sb.append("=== Failed Tests ===\n");
            for (String test : failedTests) {
                sb.append("  FAILED: ").append(test).append('\n');
                // Extract a short failure message excerpt following this test in the raw output
                String excerpt = extractFailureExcerpt(rawOutput, test);
                if (!excerpt.isEmpty()) {
                    sb.append("    ").append(excerpt).append('\n');
                }
            }
        }

        // Include truncated raw output for diagnostics
        sb.append('\n');
        sb.append("=== Raw Output (last 60 lines) ===\n");
        int startLine = Math.max(0, lines.length - 60);
        for (int i = startLine; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }

        boolean isError = exitCode != 0 && !buildSuccess;
        return new ToolResult(sb.toString(), isError);
    }

    /**
     * Attempts to extract a short failure message excerpt from the raw output
     * that appears after the given test identifier string.
     */
    private String extractFailureExcerpt(String rawOutput, String testId) {
        int idx = rawOutput.indexOf(testId);
        if (idx < 0) {
            return "";
        }
        // Look for the next non-blank line after the test ID line that looks like an error message
        int newline = rawOutput.indexOf('\n', idx);
        if (newline < 0 || newline + 1 >= rawOutput.length()) {
            return "";
        }
        String rest = rawOutput.substring(newline + 1);
        String[] lines = rest.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Stop at another test summary or separator
            if (trimmed.startsWith("Tests run:") || trimmed.startsWith("===") || trimmed.startsWith("---")) {
                break;
            }
            // Return first meaningful line as the excerpt (limit length)
            if (trimmed.length() > 120) {
                trimmed = trimmed.substring(0, 120) + "...";
            }
            return trimmed;
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // Argument helpers
    // -------------------------------------------------------------------------

    private static String stringArg(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
