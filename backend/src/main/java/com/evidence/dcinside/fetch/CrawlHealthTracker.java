package com.evidence.dcinside.fetch;

import com.evidence.dcinside.dto.CrawlHealthEvent;
import com.evidence.dcinside.http.BlockSignal;

public class CrawlHealthTracker {

    private final int consecutiveFailThreshold;
    private final boolean preferBrowserAfterThreshold;

    private FetchPhase currentPhase = FetchPhase.HTTP_DESKTOP;
    private int consecutiveFailures;
    private BlockSignal lastBlockSignal;
    private boolean preferBrowser;
    private boolean relaxBlockTracking;
    private boolean protectiveMode;

    public CrawlHealthTracker(int consecutiveFailThreshold, boolean preferBrowserAfterThreshold) {
        this.consecutiveFailThreshold = consecutiveFailThreshold;
        this.preferBrowserAfterThreshold = preferBrowserAfterThreshold;
    }

    public void reset() {
        currentPhase = FetchPhase.HTTP_DESKTOP;
        consecutiveFailures = 0;
        lastBlockSignal = null;
        preferBrowser = false;
        relaxBlockTracking = false;
        protectiveMode = false;
    }

    public void recordSuccess(FetchPhase phase) {
        currentPhase = phase;
        consecutiveFailures = 0;
        lastBlockSignal = null;
        // protectiveMode는 배치가 끝날 때까지 유지 (한번 차단되면 이후 URL도 보호 모드)
    }

    public void recordFailure(BlockSignal signal, FetchPhase phase) {
        activateProtectiveMode();
        currentPhase = phase;
        lastBlockSignal = signal;
        consecutiveFailures++;
        if (preferBrowserAfterThreshold && consecutiveFailures >= consecutiveFailThreshold) {
            preferBrowser = true;
            relaxBlockTracking = true;
        }
    }

    public boolean shouldPreferBrowser() {
        return preferBrowser;
    }

    public boolean shouldRelaxBlockTracking() {
        return relaxBlockTracking;
    }

    public boolean isProtectiveMode() {
        return protectiveMode;
    }

    public void activateProtectiveMode() {
        protectiveMode = true;
    }

    public FetchPhase currentPhase() {
        return currentPhase;
    }

    public CrawlHealthEvent snapshot(String message) {
        return new CrawlHealthEvent(
                currentPhase.id(),
                currentPhase.displayName(),
                consecutiveFailures,
                lastBlockSignal != null ? lastBlockSignal.name() : null,
                lastBlockSignal != null ? lastBlockSignal.displayName() : null,
                preferBrowser,
                relaxBlockTracking,
                protectiveMode,
                message
        );
    }
}
