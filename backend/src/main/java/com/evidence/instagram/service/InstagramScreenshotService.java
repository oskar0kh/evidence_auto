package com.evidence.instagram.service;

import com.evidence.dcinside.dto.CaptureImage;
import com.evidence.dcinside.service.screenshot.ChromeDriverFactory;
import com.evidence.dcinside.service.screenshot.ImageStitcher;
import com.evidence.instagram.http.InstagramHttpClient;
import jakarta.annotation.PostConstruct;
import org.openqa.selenium.By;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Instagram 게시물 페이지 전체 캡처 (게시글 + 하단 추천 그리드 + 푸터).
 */
@Service
public class InstagramScreenshotService {

    private static final Logger log = LoggerFactory.getLogger(InstagramScreenshotService.class);
    private static final int CAPTURE_WINDOW_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 900;
    private static final int MAX_CAPTURE_HEIGHT = 20000;
    private static final int MAX_CAPTURE_ATTEMPTS = 2;

    private final InstagramHttpClient httpClient;
    private final String configuredChromeBinary;
    private final int pageLoadTimeoutSeconds;
    private final int contentWaitSeconds;
    private final int driverStartTimeoutSeconds;
    private final int driverStartMaxAttempts;
    private final long driverRecoveryDelayMs;
    private final boolean enabled;
    private final ReentrantLock captureLock = new ReentrantLock();

    private String chromeBinary;

    public InstagramScreenshotService(
            InstagramHttpClient httpClient,
            @Value("${evidence.chrome.binary:}") String configuredChromeBinary,
            @Value("${evidence.screenshot.page-load-timeout-seconds:15}") int pageLoadTimeoutSeconds,
            @Value("${evidence.screenshot.content-wait-seconds:45}") int contentWaitSeconds,
            @Value("${evidence.screenshot.driver-start-timeout-seconds:30}") int driverStartTimeoutSeconds,
            @Value("${evidence.screenshot.driver-start-max-attempts:3}") int driverStartMaxAttempts,
            @Value("${evidence.screenshot.driver-recovery-delay-ms:2000}") long driverRecoveryDelayMs,
            @Value("${evidence.instagram.screenshot.enabled:true}") boolean enabled
    ) {
        this.httpClient = httpClient;
        this.configuredChromeBinary = configuredChromeBinary == null ? "" : configuredChromeBinary.trim();
        this.pageLoadTimeoutSeconds = pageLoadTimeoutSeconds;
        this.contentWaitSeconds = contentWaitSeconds;
        this.driverStartTimeoutSeconds = driverStartTimeoutSeconds;
        this.driverStartMaxAttempts = Math.max(1, driverStartMaxAttempts);
        this.driverRecoveryDelayMs = Math.max(0, driverRecoveryDelayMs);
        this.enabled = enabled;
    }

    @PostConstruct
    void init() throws Exception {
        if (!enabled) {
            log.info("Instagram screenshot capture is disabled");
            return;
        }
        chromeBinary = ChromeDriverFactory.resolveChromeBinary(configuredChromeBinary);
        ChromeDriverFactory.setupChromeDriver(chromeBinary);
        ChromeDriverFactory.warnIfKoreanFontsMissing();
        log.info("Instagram screenshot Chrome binary: {}", chromeBinary);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CaptureSession openSession() throws Exception {
        if (!enabled) {
            throw new IllegalStateException("Instagram screenshot capture is disabled");
        }
        captureLock.lock();
        try {
            ChromeDriver driver = createDriver();
            return new CaptureSession(driver);
        } catch (Exception e) {
            captureLock.unlock();
            throw e;
        }
    }

    public CaptureImage capturePost(String url, int excelRowNumber, String shortcode) throws Exception {
        try (CaptureSession session = openSession()) {
            return session.capturePost(url, excelRowNumber, shortcode);
        }
    }

    public static String formatFilename(int excelRowNumber, String shortcode) {
        String code = shortcode == null || shortcode.isBlank() ? "unknown" : shortcode;
        return String.format("연번 %03d_post_%s.png", excelRowNumber, code);
    }

    private ChromeDriver createDriver() throws Exception {
        ChromeDriver driver = ChromeDriverFactory.createDriverWithRecovery(
                chromeBinary,
                driverStartTimeoutSeconds,
                driverStartMaxAttempts,
                driverRecoveryDelayMs
        );
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(pageLoadTimeoutSeconds));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        driver.manage().window().setSize(new Dimension(CAPTURE_WINDOW_WIDTH, VIEWPORT_HEIGHT));
        return driver;
    }

    public final class CaptureSession implements AutoCloseable {

        private final ChromeDriver driver;
        private boolean closed;

        private CaptureSession(ChromeDriver driver) {
            this.driver = driver;
        }

        public CaptureImage capturePost(String url, int excelRowNumber, String shortcode) throws Exception {
            String filename = formatFilename(excelRowNumber, shortcode);
            Path tempFile = Files.createTempFile("instagram-capture-", ".png");
            Exception lastError = null;
            try {
                for (int attempt = 1; attempt <= MAX_CAPTURE_ATTEMPTS; attempt++) {
                    try {
                        injectSessionCookies();
                        capturePage(url, tempFile);
                        byte[] pngBytes = Files.readAllBytes(tempFile);
                        return new CaptureImage(filename, pngBytes);
                    } catch (Exception e) {
                        lastError = e;
                        log.warn("Instagram screenshot attempt {}/{} failed for {}: {}",
                                attempt, MAX_CAPTURE_ATTEMPTS, url, e.getMessage());
                        if (ChromeDriverFactory.isDriverSessionInvalid(e) || ChromeDriverFactory.isDriverStartFailure(e)) {
                            break;
                        }
                    }
                }
                throw new IllegalStateException(
                        "Instagram 스크린샷 캡처 실패: "
                                + (lastError != null && lastError.getMessage() != null
                                ? lastError.getMessage()
                                : "알 수 없는 오류"),
                        lastError
                );
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                ChromeDriverFactory.quitDriver(driver);
            } finally {
                captureLock.unlock();
            }
        }

        private void injectSessionCookies() {
            driver.get("https://www.instagram.com/");
            driver.manage().deleteAllCookies();
            Map<String, String> cookies = httpClient.exportSessionCookies();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                try {
                    driver.manage().addCookie(new Cookie.Builder(entry.getKey(), entry.getValue())
                            .domain(".instagram.com")
                            .path("/")
                            .build());
                } catch (Exception e) {
                    log.debug("Cookie inject skipped ({}): {}", entry.getKey(), e.getMessage());
                }
            }
        }

        private void capturePage(String url, Path filePath) throws Exception {
            driver.get(url);
            waitForPostContent();
            dismissOverlays();
            scrollToLoadLazyContent();
            byte[] pngBytes = captureFullPage();
            Files.write(filePath, pngBytes);
            log.info("Instagram screenshot captured: {} ({} bytes)", url, pngBytes.length);
        }

        private void waitForPostContent() {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(contentWaitSeconds));
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("article, main")));
            } catch (TimeoutException e) {
                throw new IllegalStateException("Instagram 게시물 영역을 찾지 못했습니다.", e);
            }
            try {
                TimeUnit.MILLISECONDS.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void dismissOverlays() {
            ((JavascriptExecutor) driver).executeScript("""
                    const texts = ['나중에 하기', 'Not Now', 'Accept', '허용', '닫기'];
                    document.querySelectorAll('button, [role="button"]').forEach((btn) => {
                      const label = (btn.textContent || '').trim();
                      if (texts.some((t) => label.includes(t))) {
                        try { btn.click(); } catch (_) {}
                      }
                    });
                    """);
        }

        private void scrollToLoadLazyContent() throws InterruptedException {
            JavascriptExecutor js = driver;
            long previousHeight = 0;
            for (int i = 0; i < 12; i++) {
                long height = ((Number) js.executeScript(
                        "return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);"
                )).longValue();
                js.executeScript("window.scrollTo(0, arguments[0]);", Math.min(height, MAX_CAPTURE_HEIGHT));
                TimeUnit.MILLISECONDS.sleep(350);
                if (height == previousHeight && i > 2) {
                    break;
                }
                previousHeight = height;
            }
            js.executeScript("window.scrollTo(0, 0);");
            TimeUnit.MILLISECONDS.sleep(300);
        }

        private byte[] captureFullPage() throws IOException, InterruptedException {
            long captureHeight = Math.min(resolveCaptureHeight(), MAX_CAPTURE_HEIGHT);
            int windowHeight = (int) Math.max(captureHeight, 400);
            if (windowHeight <= 8192) {
                driver.manage().window().setSize(new Dimension(CAPTURE_WINDOW_WIDTH, windowHeight));
                ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
                TimeUnit.MILLISECONDS.sleep(400);
                return captureViewport();
            }
            return captureByScrolling(captureHeight);
        }

        private long resolveCaptureHeight() {
            Number height = (Number) ((JavascriptExecutor) driver).executeScript("""
                    const footer = document.querySelector('footer, [role="contentinfo"]');
                    let bottom = Math.max(
                            document.body.scrollHeight,
                            document.documentElement.scrollHeight
                    );
                    if (footer) {
                        const rect = footer.getBoundingClientRect();
                        bottom = Math.max(bottom, Math.ceil(rect.bottom + (window.pageYOffset || 0) + 16));
                    }
                    return Math.ceil(bottom + 24);
                    """);
            return height == null ? VIEWPORT_HEIGHT : height.longValue();
        }

        private byte[] captureByScrolling(long totalHeight) throws IOException, InterruptedException {
            JavascriptExecutor js = driver;
            driver.manage().window().setSize(new Dimension(CAPTURE_WINDOW_WIDTH, VIEWPORT_HEIGHT));
            TimeUnit.MILLISECONDS.sleep(150);

            List<byte[]> parts = new ArrayList<>();
            for (long y = 0; y < totalHeight; y += VIEWPORT_HEIGHT) {
                js.executeScript("window.scrollTo(0, arguments[0]);", y);
                TimeUnit.MILLISECONDS.sleep(280);
                parts.add(captureViewport());
                if (y + VIEWPORT_HEIGHT >= totalHeight) {
                    break;
                }
            }

            byte[] stitched = parts.getFirst();
            for (int i = 1; i < parts.size(); i++) {
                stitched = ImageStitcher.appendVertically(stitched, parts.get(i));
            }
            return stitched;
        }

        private byte[] captureViewport() {
            Map<String, Object> params = new HashMap<>();
            params.put("captureBeyondViewport", false);
            params.put("fromSurface", true);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = driver.executeCdpCommand("Page.captureScreenshot", params);
            return Base64.getDecoder().decode((String) result.get("data"));
        }
    }
}
