package com.codepal.metrics;

import java.util.*;
import java.util.concurrent.*;

public class TraceStore {

    public record ToolSpan(
            String toolId,
            String toolName,
            long startMs,
            long endMs,
            boolean isError,
            String errorMsg
    ) {
        public long durationMs() {
            return endMs - startMs;
        }
    }

    public record TurnTrace(
            int turnNumber,
            long startMs,
            long endMs,
            List<ToolSpan> spans,
            int inputTokens,
            int outputTokens
    ) {
        public long durationMs() {
            return endMs - startMs;
        }

        public int errorCount() {
            int count = 0;
            for (ToolSpan span : spans) {
                if (span.isError()) count++;
            }
            return count;
        }
    }

    private static final int MAX_TRACES = 100;
    private static final TraceStore INSTANCE = new TraceStore();

    private final List<TurnTrace> traces = new CopyOnWriteArrayList<>();

    private TraceStore() {}

    public static TraceStore getInstance() {
        return INSTANCE;
    }

    public void addTrace(TurnTrace trace) {
        if (traces.size() >= MAX_TRACES) {
            traces.remove(0);
        }
        traces.add(trace);
    }

    public List<TurnTrace> getRecent(int n) {
        int size = traces.size();
        int count = Math.min(n, size);
        return List.copyOf(traces.subList(size - count, size));
    }

    public String formatLastN(int n) {
        List<TurnTrace> recent = getRecent(n);
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recent.size(); i++) {
            TurnTrace turn = recent.get(i);
            double durationSec = turn.durationMs() / 1000.0;
            int toolCount = turn.spans().size();
            int errorCount = turn.errorCount();

            sb.append(String.format("Turn %d (%.1fs): %d tool call%s, %d error%s",
                    turn.turnNumber(),
                    durationSec,
                    toolCount,
                    toolCount == 1 ? "" : "s",
                    errorCount,
                    errorCount == 1 ? "" : "s"));

            if (!turn.spans().isEmpty()) {
                sb.append(" — [");
                int maxDisplay = 5;
                int displayCount = Math.min(maxDisplay, turn.spans().size());
                for (int j = 0; j < displayCount; j++) {
                    ToolSpan span = turn.spans().get(j);
                    sb.append(span.toolName())
                      .append(" ")
                      .append(span.durationMs())
                      .append("ms");
                    if (j < displayCount - 1) {
                        sb.append(", ");
                    }
                }
                int remaining = turn.spans().size() - maxDisplay;
                if (remaining > 0) {
                    sb.append(", +").append(remaining).append(" more");
                }
                sb.append("]");
            }

            if (i < recent.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    public TurnTraceBuilder beginTurn(int turnNumber) {
        return new TurnTraceBuilder(turnNumber);
    }

    public class TurnTraceBuilder {
        private final int turnNumber;
        private final long startMs;
        private final List<ToolSpan> spans = new ArrayList<>();
        private int in;
        private int out;

        private TurnTraceBuilder(int turnNumber) {
            this.turnNumber = turnNumber;
            this.startMs = System.currentTimeMillis();
        }

        public SpanBuilder beginSpan(String toolId, String toolName) {
            return new SpanBuilder(this, toolId, toolName);
        }

        void recordSpan(ToolSpan span) {
            spans.add(span);
        }

        public TurnTrace finish(int inputTokens, int outputTokens) {
            this.in = inputTokens;
            this.out = outputTokens;
            long endMs = System.currentTimeMillis();
            TurnTrace trace = new TurnTrace(
                    turnNumber,
                    startMs,
                    endMs,
                    List.copyOf(spans),
                    in,
                    out
            );
            TraceStore.this.addTrace(trace);
            return trace;
        }
    }

    public static class SpanBuilder {
        private final TurnTraceBuilder parent;
        private final String toolId;
        private final String toolName;
        private final long startMs;

        private SpanBuilder(TurnTraceBuilder parent, String toolId, String toolName) {
            this.parent = parent;
            this.toolId = toolId;
            this.toolName = toolName;
            this.startMs = System.currentTimeMillis();
        }

        public TurnTraceBuilder end(boolean isError, String errorMsg) {
            long endMs = System.currentTimeMillis();
            ToolSpan span = new ToolSpan(toolId, toolName, startMs, endMs, isError, errorMsg);
            parent.recordSpan(span);
            return parent;
        }
    }
}
