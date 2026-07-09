package com.evidence.dcinside;

import java.util.concurrent.TimeoutException;

/**
 * 단일 URL 또는 HTTP 요청에 대한 최대 처리 시간 예산.
 */
public final class CrawlDeadline {

    private final long deadlineEpochMs;

    private CrawlDeadline(long deadlineEpochMs) {
        this.deadlineEpochMs = deadlineEpochMs;
    }

    public static CrawlDeadline fromNow(long maxDurationMs) {
        if (maxDurationMs <= 0) {
            return disabled();
        }
        return new CrawlDeadline(System.currentTimeMillis() + maxDurationMs);
    }

    public static CrawlDeadline disabled() {
        return new CrawlDeadline(Long.MAX_VALUE);
    }

    public boolean isEnabled() {
        return deadlineEpochMs != Long.MAX_VALUE;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= deadlineEpochMs;
    }

    public long remainingMs() {
        return Math.max(0, deadlineEpochMs - System.currentTimeMillis());
    }

    public void check() throws TimeoutException {
        if (isExpired()) {
            throw new TimeoutException("URL 처리 시간 예산을 초과했습니다.");
        }
    }

    public long cappedSleepMs(long requestedMs) {
        if (!isEnabled()) {
            return requestedMs;
        }
        return Math.min(requestedMs, remainingMs());
    }
}
