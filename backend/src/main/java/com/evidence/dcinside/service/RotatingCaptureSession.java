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
    private ScreenshotService.CaptureSession current;
    private int urlsSinceRotation;

    public RotatingCaptureSession(ScreenshotService screenshotService, int rotateEveryUrls) {
        this.screenshotService = screenshotService;
        this.rotateEveryUrls = Math.max(0, rotateEveryUrls);
    }

    public ScreenshotService.CaptureSession current() throws Exception {
        if (current == null) {
            openFreshSession();
        }
        return current;
    }

    public void afterUrlProcessed() {
        if (rotateEveryUrls <= 0 || current == null) {
            return;
        }
        urlsSinceRotation++;
        if (urlsSinceRotation < rotateEveryUrls) {
            return;
        }
        try {
            rotateSession("scheduled");
        } catch (Exception e) {
            log.warn("Chrome 세션 주기 교체 실패: {}", e.getMessage());
        }
    }

    public void rotateSession(String reason) throws Exception {
        closeCurrentQuietly();
        openFreshSession();
        log.info("Chrome capture session rotated ({})", reason);
    }

    private void openFreshSession() throws Exception {
        current = screenshotService.openCaptureSession();
        urlsSinceRotation = 0;
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
