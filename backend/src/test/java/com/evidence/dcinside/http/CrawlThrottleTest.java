package com.evidence.dcinside.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlThrottleTest {

    @Test
    void fastModeSkipsDelayBelowThreshold() {
        CrawlThrottle throttle = new CrawlThrottle(
                1000, 500, 1000,
                3000, 8000,
                300, 1000,
                30000, 60000
        );
        long started = System.nanoTime();
        assertDoesNotThrow(() -> throttle.sleepBeforeRequest(false, 999));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 50, "expected no meaningful delay below threshold");
    }

    @Test
    void fastModeAppliesDelayAtOrAboveThreshold() {
        CrawlThrottle throttle = new CrawlThrottle(
                1000, 500, 500,
                3000, 8000,
                300, 1000,
                30000, 60000
        );
        long started = System.nanoTime();
        assertDoesNotThrow(() -> throttle.sleepBeforeRequest(false, 1000));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs >= 500, "expected delay at threshold");
    }
}
