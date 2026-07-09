package com.evidence.dcinside.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlTelemetryTest {

    @Test
    void prefixesMessageWithWallClockAndElapsedTime() {
        long startedAt = System.currentTimeMillis() - ((14 * 60 + 20) * 1000L);
        String formatted = CrawlTelemetry.formatTimestampedMessage("ChromeDriver 복구 재시도 1/3", startedAt);
        assertTrue(formatted.matches("^\\[\\d{2}:\\d{2}/00:14:20\\] ChromeDriver 복구 재시도 1/3$"));
    }

    @Test
    void keepsAlreadyTimestampedMessage() {
        String input = "[17:30/00:14:20] ChromeDriver 복구 재시도 1/3";
        assertEquals(input, CrawlTelemetry.formatTimestampedMessage(input, System.currentTimeMillis()));
    }
}
