package com.evidence.dcinside.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 장시간 크롤링 중 Chrome/Selenium 세션을 주기적으로 재생성합니다.
 */
public class RotatingCaptureSession implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RotatingCaptureSession.class);

    private final ScreenshotService screenshotService;
    private final int rotateEveryUrls;
    private final CrawlTelemetry crawlTelemetry;
    private ScreenshotService.CaptureSession current;
    private int urlsSinceRotation;

    public RotatingCaptureSession(
            ScreenshotService screenshotService,
            int rotateEveryUrls,
            CrawlTelemetry crawlTelemetry
    ) {
        this.screenshotService = screenshotService;
        this.rotateEveryUrls = Math.max(0, rotateEveryUrls);
        this.crawlTelemetry = crawlTelemetry;
    }

    public ScreenshotService.CaptureSession current() throws Exception {
        if (rotateEveryUrls > 0 && current != null && urlsSinceRotation >= rotateEveryUrls) {
            rotateSession("scheduled");
        }
        if (current == null) {
            openFreshSession("initial");
        }
        return current;
    }

    public void afterUrlProcessed() {
        if (current == null) {
            return;
        }
        urlsSinceRotation++;
    }

    public void rotateSession(String reason) throws Exception {
        crawlTelemetry.record("Chrome 세션 교체 시작 (" + reason + ")");
        closeCurrentQuietly();
        openFreshSession(reason);
        log.info("Chrome capture session rotated ({})", reason);
        crawlTelemetry.record("Chrome 세션 교체 완료 (" + reason + ")");
    }

    private void openFreshSession(String reason) throws Exception {
        try {
            current = screenshotService.openCaptureSession();
            urlsSinceRotation = 0;
            crawlTelemetry.record("Chrome capture session opened (" + reason + ")");
        } catch (Exception e) {
            crawlTelemetry.record("Chrome 세션 시작 실패 (" + reason + "): " + e.getMessage());
            throw e;
        }
    }

    private void closeCurrentQuietly() {
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (Exception e) {
            log.debug("Chrome capture session close failed: {}", e.getMessage());
        } finally {
            current = null;
        }
    }

    @Override
    public void close() {
        closeCurrentQuietly();
    }
}
