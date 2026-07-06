package com.evidence.dto;

import java.util.Map;

public record UrlTiming(
        String url,
        boolean success,
        long totalMs,
        Map<String, Long> steps
) {
}
