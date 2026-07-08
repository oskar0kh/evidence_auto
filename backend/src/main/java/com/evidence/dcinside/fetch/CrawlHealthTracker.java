package com.evidence.dcinside.fetch;

import com.evidence.dcinside.dto.CrawlHealthEvent;
import com.evidence.dcinside.http.BlockSignal;

public class CrawlHealthTracker {

    private final int consecutiveFailThreshold;
    private final boolean preferBrowserAfterThreshold;
    private final int protectiveSuccessesToRelease;

    private FetchPhase currentPhase = FetchPhase.HTTP_DESKTOP;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private BlockSignal lastBlockSignal;
    private boolean preferBrowser;
    private boolean relaxBlockTracking;
    private boolean protectiveMode;

    public CrawlHealthTracker(
            int consecutiveFailThreshold,
            boolean preferBrowserAfterThreshold,
            int protectiveSuccessesToRelease
    ) {
        this.consecutiveFailThreshold = consecutiveFailThreshold;
        this.preferBrowserAfterThreshold = preferBrowserAfterThreshold;
        this.protectiveSuccessesToRelease = Math.max(1, protectiveSuccessesToRelease);
    }

    public void reset() {
        currentPhase = FetchPhase.HTTP_DESKTOP;
        consecutiveFailures = 0;
        consecutiveSuccesses = 0;
        lastBlockSignal = null;
        preferBrowser = false;
        relaxBlockTracking = false;
        protectiveMode = false;
    }

    public void recordSuccess(FetchPhase phase) {
        currentPhase = phase;
        consecutiveFailures = 0;
        lastBlockSignal = null;
        consecutiveSuccesses++;
        if (protectiveMode && consecutiveSuccesses >= protectiveSuccessesToRelease) {
            deactivateProtectiveMode();
        }
    }

    public void recordFailure(BlockSignal signal, FetchPhase phase) {
        activateProtectiveMode();
        currentPhase = phase;
        lastBlockSignal = signal;
        consecutiveFailures++;
        consecutiveSuccesses = 0;
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
        if (!protectiveMode) {
            protectiveMode = true;
            consecutiveSuccesses = 0;
        }
    }

    public void deactivateProtectiveMode() {
        protectiveMode = false;
        preferBrowser = false;
        relaxBlockTracking = false;
        consecutiveSuccesses = 0;
        consecutiveFailures = 0;
        lastBlockSignal = null;
        currentPhase = FetchPhase.HTTP_DESKTOP;
    }

    public int consecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public int protectiveSuccessesToRelease() {
        return protectiveSuccessesToRelease;
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
