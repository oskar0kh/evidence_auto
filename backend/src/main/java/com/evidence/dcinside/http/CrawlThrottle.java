package com.evidence.dcinside.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class CrawlThrottle {

    private final int requestDelayMinMs;
    private final int requestDelayMaxMs;
    private final int commentPageDelayMs;
    private final int screenshotRetryDelayMs;

    public CrawlThrottle(
            @Value("${evidence.crawl.request-delay-min-ms:800}") int requestDelayMinMs,
            @Value("${evidence.crawl.request-delay-max-ms:2000}") int requestDelayMaxMs,
            @Value("${evidence.crawl.comment-page-delay-ms:300}") int commentPageDelayMs,
            @Value("${evidence.screenshot.retry-delay-ms:1000}") int screenshotRetryDelayMs
    ) {
        this.requestDelayMinMs = requestDelayMinMs;
        this.requestDelayMaxMs = Math.max(requestDelayMinMs, requestDelayMaxMs);
        this.commentPageDelayMs = commentPageDelayMs;
        this.screenshotRetryDelayMs = screenshotRetryDelayMs;
    }

    public void sleepBeforeRequest() throws InterruptedException {
        sleepBeforeRequest(false);
    }

    public void sleepBeforeRequest(boolean protectiveMode) throws InterruptedException {
        if (!protectiveMode) {
            return;
        }
        sleepRandom(requestDelayMinMs, requestDelayMaxMs);
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
