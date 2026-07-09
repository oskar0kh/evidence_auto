package com.evidence.dcinside.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class CrawlThrottle {

    private final int fastModeDelayThreshold;
    private final int fastModeDelayAfterThresholdMinMs;
    private final int fastModeDelayAfterThresholdMaxMs;
    private final int protectiveDelayMinMs;
    private final int protectiveDelayMaxMs;
    private final int commentPageDelayMs;
    private final int screenshotRetryDelayMs;
    private final int connectionCooldownMinMs;
    private final int connectionCooldownMaxMs;

    public CrawlThrottle(
            @Value("${evidence.crawl.fast-mode-delay-threshold:1000}") int fastModeDelayThreshold,
            @Value("${evidence.crawl.fast-mode-delay-after-threshold-min-ms:500}") int fastModeDelayAfterThresholdMinMs,
            @Value("${evidence.crawl.fast-mode-delay-after-threshold-max-ms:1000}") int fastModeDelayAfterThresholdMaxMs,
            @Value("${evidence.crawl.protective-request-delay-min-ms:3000}") int protectiveDelayMinMs,
            @Value("${evidence.crawl.protective-request-delay-max-ms:8000}") int protectiveDelayMaxMs,
            @Value("${evidence.crawl.comment-page-delay-ms:300}") int commentPageDelayMs,
            @Value("${evidence.screenshot.retry-delay-ms:1000}") int screenshotRetryDelayMs,
            @Value("${evidence.crawl.connection-cooldown-min-ms:30000}") int connectionCooldownMinMs,
            @Value("${evidence.crawl.connection-cooldown-max-ms:60000}") int connectionCooldownMaxMs
    ) {
        this.fastModeDelayThreshold = Math.max(0, fastModeDelayThreshold);
        this.fastModeDelayAfterThresholdMinMs = fastModeDelayAfterThresholdMinMs;
        this.fastModeDelayAfterThresholdMaxMs = Math.max(
                fastModeDelayAfterThresholdMinMs,
                fastModeDelayAfterThresholdMaxMs
        );
        this.protectiveDelayMinMs = protectiveDelayMinMs;
        this.protectiveDelayMaxMs = Math.max(protectiveDelayMinMs, protectiveDelayMaxMs);
        this.commentPageDelayMs = commentPageDelayMs;
        this.screenshotRetryDelayMs = screenshotRetryDelayMs;
        this.connectionCooldownMinMs = connectionCooldownMinMs;
        this.connectionCooldownMaxMs = Math.max(connectionCooldownMinMs, connectionCooldownMaxMs);
    }

    public void sleepBeforeRequest(boolean protectiveMode) throws InterruptedException {
        sleepBeforeRequest(protectiveMode, 0);
    }

    /**
     * @param processedPostUrlCount 이미 처리 완료(성공·실패)된 게시글 URL 수
     */
    public void sleepBeforeRequest(boolean protectiveMode, int processedPostUrlCount) throws InterruptedException {
        if (protectiveMode) {
            sleepRandom(protectiveDelayMinMs, protectiveDelayMaxMs);
            return;
        }
        if (processedPostUrlCount >= fastModeDelayThreshold) {
            sleepRandom(fastModeDelayAfterThresholdMinMs, fastModeDelayAfterThresholdMaxMs);
        }
    }

    public void sleepBeforeCommentPage() throws InterruptedException {
        sleepBeforeCommentPage(false);
    }

    public void sleepBeforeCommentPage(boolean protectiveMode) throws InterruptedException {
        if (!protectiveMode || commentPageDelayMs <= 0) {
            return;
        }
        Thread.sleep(commentPageDelayMs);
    }

    public void sleepBeforeScreenshotRetry() throws InterruptedException {
        if (screenshotRetryDelayMs <= 0) {
            return;
        }
        int jitter = ThreadLocalRandom.current().nextInt(0, Math.max(1, screenshotRetryDelayMs / 4));
        Thread.sleep(screenshotRetryDelayMs + jitter);
    }

    public void sleepConnectionCooldown() throws InterruptedException {
        sleepRandom(connectionCooldownMinMs, connectionCooldownMaxMs);
    }

    private static void sleepRandom(int minMs, int maxMs) throws InterruptedException {
        if (maxMs <= 0) {
            return;
        }
        int bound = Math.max(minMs, maxMs);
        int delay = minMs >= bound
                ? minMs
                : ThreadLocalRandom.current().nextInt(minMs, bound + 1);
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }
}
