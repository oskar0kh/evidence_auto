package com.evidence.dcinside.dto;

public record CrawlProgressEvent(
        int completed,
        int total,
        String currentUrl,
        String stage,
        int successCount,
        int failCount,
        Integer urlAttempt,
        Integer urlAttemptMax,
        String urlAttemptPhase,
        Long urlDeadlineRemainingMs
) {
    public CrawlProgressEvent(
            int completed,
            int total,
            String currentUrl,
            String stage,
            int successCount,
            int failCount
    ) {
        this(completed, total, currentUrl, stage, successCount, failCount, null, null, null, null);
    }
}
