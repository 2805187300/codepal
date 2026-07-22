package com.mewcode.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class MetricsCollector {

    private static final MetricsCollector INSTANCE = new MetricsCollector();

    private final AtomicLong totalToolCalls = new AtomicLong(0);
    private final AtomicLong toolErrors = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> toolCallCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> toolErrorCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> toolTotalLatencyMs = new ConcurrentHashMap<>();
    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicLong totalTurns = new AtomicLong(0);
    private final AtomicLong agentLoopErrors = new AtomicLong(0);
    private final long startTimeMs;

    private MetricsCollector() {
        this.startTimeMs = System.currentTimeMillis();
    }

    public static MetricsCollector getInstance() {
        return INSTANCE;
    }

    public void recordToolCall(String toolName, long latencyMs, boolean isError) {
        totalToolCalls.incrementAndGet();
        toolCallCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        toolTotalLatencyMs.computeIfAbsent(toolName, k -> new AtomicLong(0)).addAndGet(latencyMs);
        if (isError) {
            toolErrors.incrementAndGet();
            toolErrorCounts.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public void recordTokens(int inputTokens, int outputTokens) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
    }

    public void recordTurnComplete() {
        totalTurns.incrementAndGet();
    }

    public void recordAgentLoopError() {
        agentLoopErrors.incrementAndGet();
    }

    public String formatReport() {
        long uptimeMs = System.currentTimeMillis() - startTimeMs;
        long uptimeSec = uptimeMs / 1000;
        long minutes = uptimeSec / 60;
        long seconds = uptimeSec % 60;

        long totalCalls = totalToolCalls.get();
        long totalErr = toolErrors.get();
        double errorRate = totalCalls > 0 ? (totalErr * 100.0 / totalCalls) : 0.0;

        StringBuilder sb = new StringBuilder();
        sb.append("=== MewCode Agent Metrics ===\n");
        sb.append(String.format("Uptime: %dm %ds%n", minutes, seconds));
        sb.append(String.format("Turns completed: %d%n", totalTurns.get()));
        sb.append("\n");
        sb.append("Token Usage:\n");
        sb.append(String.format("  Input:  %,d%n", totalInputTokens.get()));
        sb.append(String.format("  Output: %,d%n", totalOutputTokens.get()));
        sb.append("\n");
        sb.append(String.format("Tool Calls: %d total, %d errors (%.1f%% error rate)%n",
                totalCalls, totalErr, errorRate));
        sb.append("\n");
        sb.append("Per-Tool Stats:\n");

        for (Map.Entry<String, AtomicLong> entry : toolCallCounts.entrySet()) {
            String toolName = entry.getKey();
            long calls = entry.getValue().get();
            long errors = toolErrorCounts.getOrDefault(toolName, new AtomicLong(0)).get();
            long totalLatency = toolTotalLatencyMs.getOrDefault(toolName, new AtomicLong(0)).get();
            long avgLatency = calls > 0 ? totalLatency / calls : 0;

            sb.append(String.format("  %-14s calls=%-5d errors=%-5d avg_latency=%dms%n",
                    toolName, calls, errors, avgLatency));
        }

        sb.append("\n");
        sb.append(String.format("Agent Loop Errors: %d", agentLoopErrors.get()));

        return sb.toString();
    }
}
