package com.evidence.dcinside.dto;

import java.util.Map;

public record UrlTiming(
        String url,
        boolean success,
        long totalMs,
        Map<String, Long> steps,
        Integer urlAttempt,
        Integer urlAttemptMax,
        String urlAttemptPhase,
        Long urlDeadlineRemainingMs
) {
    public UrlTiming(String url, boolean success, long totalMs, Map<String, Long> steps) {
        this(url, success, totalMs, steps, null, null, null, null);
    }
}
