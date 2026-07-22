package com.mewcode.eval;

import com.mewcode.agent.Agent;
import com.mewcode.agent.AgentEvent;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.tool.ToolRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class EvalRunner {

    public record EvalResult(
            String caseId,
            boolean passed,
            String failReason,
            long durationMs,
            int inputTokens,
            int outputTokens,
            String agentOutput
    ) {}

    public record EvalReport(
            List<EvalResult> results,
            int passed,
            int failed,
            long totalDurationMs
    ) {
        public String format() {
            int total = passed + failed;
            double pct = total == 0 ? 0.0 : (passed * 100.0 / total);
            double totalSec = totalDurationMs / 1000.0;

            var sb = new StringBuilder();
            sb.append("=== Eval Report ===\n");
            sb.append(String.format("Passed: %d/%d (%.1f%%)\n", passed, total, pct));
            sb.append(String.format("Total time: %.1fs\n", totalSec));
            sb.append("\n");

            for (EvalResult r : results) {
                double sec = r.durationMs() / 1000.0;
                if (r.passed()) {
                    sb.append(String.format("[PASS] %s (%.1fs)\n", r.caseId(), sec));
                } else {
                    sb.append(String.format("[FAIL] %s (%.1fs): %s\n",
                            r.caseId(), sec, r.failReason() != null ? r.failReason() : "unknown"));
                }
            }

            // Remove trailing newline for clean output
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
                sb.deleteCharAt(sb.length() - 1);
            }
            return sb.toString();
        }
    }

    private final ProviderConfig providerConfig;
    private final ToolRegistry toolRegistry;

    public EvalRunner(ProviderConfig providerConfig, ToolRegistry toolRegistry) {
        this.providerConfig = providerConfig;
        this.toolRegistry = toolRegistry;
    }

    public EvalReport run(List<EvalCase> cases) {
        long reportStart = System.currentTimeMillis();
        List<EvalResult> results = new ArrayList<>(cases.size());

        for (EvalCase evalCase : cases) {
            EvalResult result = runSingle(evalCase);
            results.add(result);
        }

        int passed = 0;
        int failed = 0;
        for (EvalResult r : results) {
            if (r.passed()) {
                passed++;
            } else {
                failed++;
            }
        }

        long totalDurationMs = System.currentTimeMillis() - reportStart;
        return new EvalReport(List.copyOf(results), passed, failed, totalDurationMs);
    }

    private EvalResult runSingle(EvalCase evalCase) {
        long start = System.currentTimeMillis();
        try {
            // 1. Create LlmClient and Agent with a fresh ConversationManager
            LlmClient client = LlmClient.create(providerConfig, "You are a helpful coding assistant.");
            var conv = new ConversationManager();
            conv.addUserMessage(evalCase.prompt());

            Agent agent = new Agent(client, toolRegistry, providerConfig.getProtocol(), providerConfig);

            // Collect all agent output text and token usage
            var outputBuf = new StringBuilder();
            int[] tokens = {0, 0}; // [inputTokens, outputTokens]

            var queue = new LinkedBlockingQueue<AgentEvent>(256);
            agent.run(conv, queue);

            // Drain queue until LoopComplete or ErrorEvent
            while (true) {
                AgentEvent ev = queue.poll(60, TimeUnit.SECONDS);
                if (ev == null) {
                    long duration = System.currentTimeMillis() - start;
                    return new EvalResult(
                            evalCase.id(), false, "Timeout waiting for agent",
                            duration, tokens[0], tokens[1], outputBuf.toString());
                }
                if (ev instanceof AgentEvent.StreamText t) {
                    outputBuf.append(t.text());
                }
                if (ev instanceof AgentEvent.UsageEvent u) {
                    tokens[0] = u.inputTokens();
                    tokens[1] = u.outputTokens();
                }
                if (ev instanceof AgentEvent.LoopComplete) {
                    break;
                }
                if (ev instanceof AgentEvent.ErrorEvent e) {
                    long duration = System.currentTimeMillis() - start;
                    return new EvalResult(
                            evalCase.id(), false, "Agent error: " + e.message(),
                            duration, tokens[0], tokens[1], outputBuf.toString());
                }
            }

            String output = outputBuf.toString();
            long duration = System.currentTimeMillis() - start;

            // 2. Check requiredOutputKeywords
            List<String> requiredKeywords = evalCase.requiredOutputKeywords();
            if (requiredKeywords != null) {
                for (String kw : requiredKeywords) {
                    if (!output.contains(kw)) {
                        return new EvalResult(evalCase.id(), false,
                                "keyword '" + kw + "' not found in output",
                                duration, tokens[0], tokens[1], output);
                    }
                }
            }

            // 3. Check expectedFileEdits (files must exist after agent run)
            List<String> expectedFiles = evalCase.expectedFileEdits();
            if (expectedFiles != null) {
                for (String filePath : expectedFiles) {
                    if (!Files.exists(Path.of(filePath))) {
                        return new EvalResult(evalCase.id(), false,
                                "expected file not created: " + filePath,
                                duration, tokens[0], tokens[1], output);
                    }
                }
            }

            // 4. Run successBashCheck if non-null
            if (evalCase.successBashCheck() != null && !evalCase.successBashCheck().isBlank()) {
                Process p = new ProcessBuilder("bash", "-c", evalCase.successBashCheck())
                        .redirectErrorStream(true)
                        .start();
                boolean done = p.waitFor(30, TimeUnit.SECONDS);
                if (!done || p.exitValue() != 0) {
                    String exitDesc = done ? String.valueOf(p.exitValue()) : "timeout";
                    return new EvalResult(evalCase.id(), false,
                            "bash check failed (exit " + exitDesc + ")",
                            duration, tokens[0], tokens[1], output);
                }
            }

            return new EvalResult(evalCase.id(), true, null, duration, tokens[0], tokens[1], output);

        } catch (Exception e) {
            return new EvalResult(evalCase.id(), false,
                    "exception: " + e.getMessage(),
                    System.currentTimeMillis() - start, 0, 0, "");
        }
    }
}
