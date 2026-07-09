package com.evidence.dcinside.util;

import java.util.LinkedHashMap;
import java.util.Map;

public record StepTimings(String scope, Map<String, Long> steps, long totalMs) {

    public static StepTimings merge(StepTimings... timings) {
        Map<String, Long> merged = new LinkedHashMap<>();
        long totalMs = 0;
        for (StepTimings timing : timings) {
            if (timing == null) {
                continue;
            }
            timing.steps().forEach((name, ms) -> merged.merge(normalizeStepName(name), ms, Long::sum));
            totalMs += timing.totalMs();
        }
        return new StepTimings("merged", Map.copyOf(merged), totalMs);
    }

    public static String normalizeStepName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("\\s*\\([^)]*\\)$", "").trim();
    }

    public Map<String, Long> normalizedSteps() {
        Map<String, Long> normalized = new LinkedHashMap<>();
        steps.forEach((name, ms) -> normalized.merge(normalizeStepName(name), ms, Long::sum));
        return normalized;
    }
}
