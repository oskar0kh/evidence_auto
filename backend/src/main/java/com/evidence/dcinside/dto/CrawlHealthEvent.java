package com.evidence.dcinside.dto;

public record CrawlHealthEvent(
        String currentPhase,
        String currentPhaseLabel,
        int consecutiveFailures,
        String lastBlockSignal,
        String lastBlockSignalLabel,
        boolean preferBrowser,
        boolean relaxBlockTracking,
        boolean protectiveMode,
        String message
) {
}
