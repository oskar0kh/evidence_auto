package com.evidence.dcinside.dto;

public record CrawlProgressEvent(
        int completed,
        int total,
        String currentUrl,
        String stage,
        int successCount,
        int failCount
) {
}
