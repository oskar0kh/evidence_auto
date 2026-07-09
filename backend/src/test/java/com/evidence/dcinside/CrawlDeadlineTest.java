package com.evidence.dcinside;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlDeadlineTest {

    @Test
    void disabledDeadlineNeverExpires() throws Exception {
        CrawlDeadline deadline = CrawlDeadline.disabled();
        assertFalse(deadline.isEnabled());
        deadline.check();
    }

    @Test
    void expiresAfterDuration() throws Exception {
        CrawlDeadline deadline = CrawlDeadline.fromNow(50);
        Thread.sleep(60);
        assertTrue(deadline.isExpired());
        assertThrows(TimeoutException.class, deadline::check);
    }

    @Test
    void capsSleepToRemainingBudget() {
        CrawlDeadline deadline = CrawlDeadline.fromNow(100);
        assertTrue(deadline.cappedSleepMs(500) <= 100);
    }
}
