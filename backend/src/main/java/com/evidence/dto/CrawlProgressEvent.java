package com.evidence.dto;

public record CrawlProgressEvent(
        int completed,
        int total,
        String currentUrl,
        String stage,
        int successCount,
        int failCount
) {
}
