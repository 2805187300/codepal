package com.mewcode.metrics;

public class MetricsCommand {

    public static String handle() {
        StringBuilder sb = new StringBuilder();
        sb.append(MetricsCollector.getInstance().formatReport());
        sb.append("\n\n");
        sb.append("=== Recent Turn Traces ===\n");
        sb.append(TraceStore.getInstance().formatLastN(5));
        return sb.toString();
    }
}
