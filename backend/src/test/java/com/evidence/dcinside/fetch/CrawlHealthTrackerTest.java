package com.evidence.dcinside.fetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlHealthTrackerTest {

    @Test
    void activatesProtectiveModeOnFirstFailure() {
        CrawlHealthTracker tracker = new CrawlHealthTracker(3, true);
        assertFalse(tracker.isProtectiveMode());

        tracker.recordFailure(com.evidence.dcinside.http.BlockSignal.HTTP_ERROR, FetchPhase.HTTP_DESKTOP);
        assertTrue(tracker.isProtectiveMode());
    }

    @Test
    void preferBrowserAfterConsecutiveFailures() {
        CrawlHealthTracker tracker = new CrawlHealthTracker(3, true);
        tracker.recordFailure(com.evidence.dcinside.http.BlockSignal.HTTP_ERROR, FetchPhase.HTTP_DESKTOP);
        tracker.recordFailure(com.evidence.dcinside.http.BlockSignal.HTTP_ERROR, FetchPhase.HTTP_DESKTOP);
        assertFalse(tracker.shouldPreferBrowser());

        tracker.recordFailure(com.evidence.dcinside.http.BlockSignal.HTTP_ERROR, FetchPhase.HTTP_DESKTOP);
        assertTrue(tracker.shouldPreferBrowser());
        assertTrue(tracker.shouldRelaxBlockTracking());
    }
}
