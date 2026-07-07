package com.evidence.dcinside.service;

import com.evidence.dto.CaptureImage;
import com.evidence.dto.TimedResult;
import com.evidence.service.StageTimedException;
import com.evidence.util.StepTimer;
import com.evidence.util.StepTimings;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PostConstruct;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Base64;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScreenshotService {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotService.class);

    private static final Pattern POST_NO_PATTERN = Pattern.compile("[?&]no=(\\d+)");
    private static final Pattern CHROME_VERSION_PATTERN = Pattern.compile("(\\d+)\\.");

    // 캡처 대상: wrap_inner 안의 본문 article (이슈박스 article 제외)
    private static final String CAPTURE_TARGET_CSS = "div.wrap_inner article:has(.gallview_head)";
    private static final String COMMENT_BOX_CSS = "#comment_wrap_%s .comment_box";
    private static final int CAPTURE_WINDOW_WIDTH = 1280;
    private static final int MAX_CAPTURE_HEIGHT = 12000;
    private static final int MAX_STITCHED_HEIGHT = 50000;
    private static final int MAX_COMMENT_PAGES = 20;
    private static final int MAX_CAPTURE_ATTEMPTS = 2;

    private final String configuredChromeBinary;
    private final long commentWaitMs;
    private final int pageLoadTimeoutSeconds;
    private final int contentWaitSeconds;
    private final boolean blockTracking;
    private final boolean captureAllCommentPages;
    private final ReentrantLock captureLock = new ReentrantLock();

    private String chromeBinary;

    // 스크린샷 서비스 생성자
    public ScreenshotService(
            @Value("${evidence.chrome.binary:}") String configuredChromeBinary,
            @Value("${evidence.screenshot.comment-wait-ms:1500}") long commentWaitMs,
            @Value("${evidence.screenshot.page-load-timeout-seconds:15}") int pageLoadTimeoutSeconds,
            @Value("${evidence.screenshot.content-wait-seconds:45}") int contentWaitSeconds,
            @Value("${evidence.screenshot.block-tracking:true}") boolean blockTracking,
            @Value("${evidence.screenshot.capture-all-comment-pages:true}") boolean captureAllCommentPages
    ) {
        this.configuredChromeBinary = configuredChromeBinary == null ? "" : configuredChromeBinary.trim();
        this.commentWaitMs = commentWaitMs;
        this.pageLoadTimeoutSeconds = pageLoadTimeoutSeconds;
        this.contentWaitSeconds = contentWaitSeconds;
        this.blockTracking = blockTracking;
        this.captureAllCommentPages = captureAllCommentPages;
    }

    // 스크린샷 서비스 초기화
    @PostConstruct
    void init() throws Exception {
        chromeBinary = resolveChromeBinary();
        setupChromeDriver(chromeBinary);
        warnIfKoreanFontsMissing();
        log.info("Screenshot Chrome binary: {}, blockTracking={}", chromeBinary, blockTracking);
    }

    /**
     * 배치 크롤링 동안 Chrome을 한 번만 띄우고 URL마다 navigate·캡처합니다.
     * try-with-resources로 닫아 Chrome과 락을 반드시 해제하세요.
     */
    public CaptureSession openCaptureSession() throws Exception {
        captureLock.lock();
        try {
            ChromeDriver driver = createAndConfigureDriver(null);
            log.info("Screenshot capture session opened");
            return new CaptureSession(driver);
        } catch (Exception e) {
            captureLock.unlock();
            throw e;
        }
    }

    // 스크린샷 캡처 (게시글~댓글 영역, 단일 URL용)
    public TimedResult<CaptureImage> captureFullPage(String url, int excelRowNumber, String postNo) throws Exception {
        try (CaptureSession session = openCaptureSession()) {
            return session.captureFullPage(url, excelRowNumber, postNo);
        }
    }

    public final class CaptureSession implements AutoCloseable {

        private ChromeDriver driver;
        private boolean closed;

        private CaptureSession(ChromeDriver driver) {
            this.driver = driver;
        }

        public TimedResult<CaptureImage> captureFullPage(String url, int excelRowNumber, String postNo) throws Exception {
            String filename = formatFilename(excelRowNumber, postNo);
            Path tempFile = Files.createTempFile("evidence-capture-", ".png");

            Exception lastError = null;
            StepTimings lastTimings = null;
            try {
                for (int attempt = 1; attempt <= MAX_CAPTURE_ATTEMPTS; attempt++) {
                    String scope = attempt == 1
                            ? "screenshot " + url
                            : "screenshot " + url + " attempt=" + attempt;
                    StepTimer timer = new StepTimer(log, scope);
                    try {
                        ensureDriver(timer);
                        captureWithDriver(driver, url, postNo, tempFile, timer);
                        byte[] pngBytes = Files.readAllBytes(tempFile);
                        timer.step("read-temp-file (" + pngBytes.length + " bytes)");
                        StepTimings timings = timer.finish();
                        return new TimedResult<>(new CaptureImage(filename, pngBytes), timings);
                    } catch (Exception e) {
                        lastError = e;
                        lastTimings = timer.finish();
                        log.warn("Screenshot attempt {}/{} failed for {}: {}", attempt, MAX_CAPTURE_ATTEMPTS, url, e.getMessage());
                        if (isDriverSessionInvalid(e)) {
                            invalidateDriver();
                        }
                        if (attempt < MAX_CAPTURE_ATTEMPTS) {
                            TimeUnit.SECONDS.sleep(1);
                        }
                    }
                }

                throw new StageTimedException(
                        "screenshot",
                        new IllegalStateException(
                                "스크린샷 캡처 실패: " + (lastError != null && lastError.getMessage() != null
                                        ? lastError.getMessage()
                                        : "알 수 없는 오류")
                                        + " (Chrome: " + chromeBinary + ")",
                                lastError
                        ),
                        lastTimings
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
                quitDriver(driver);
                log.info("Screenshot capture session closed");
            } finally {
                captureLock.unlock();
            }
        }

        private void ensureDriver(StepTimer timer) throws Exception {
            if (driver != null && isDriverAlive(driver)) {
                return;
            }
            invalidateDriver();
            driver = createAndConfigureDriver(timer);
        }

        private void invalidateDriver() {
            quitDriver(driver);
            driver = null;
        }
    }

    private ChromeDriver createAndConfigureDriver(StepTimer timer) throws Exception {
        ChromeDriver chromeDriver = createDriver();
        if (timer != null) {
            timer.step("create-driver");
        }
        if (blockTracking) {
            TrackingDomainBlocker.apply(chromeDriver);
            if (timer != null) {
                timer.step("apply-tracking-blocker");
            }
        }
        chromeDriver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(pageLoadTimeoutSeconds));
        chromeDriver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30));
        return chromeDriver;
    }

    private void captureWithDriver(ChromeDriver driver, String url, String postNo, Path filePath, StepTimer timer)
            throws Exception {
        resetBeforeCapture(driver);
        driver.get(url);
        timer.step("page-navigate");

        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(contentWaitSeconds));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.gallview_head")));
        timer.step("wait-gallview-head");

        waitForCommentsIfNeeded(driver, postNo);
        timer.step("wait-comments");

        WebElement captureTarget = findCaptureTarget(driver, wait);
        timer.step("find-capture-target");

        byte[] pngBytes = captureWithCommentPages(driver, captureTarget, postNo);
        timer.step("capture-images (" + pngBytes.length + " bytes)");
        Files.write(filePath, pngBytes);
        timer.step("write-temp-file");
    }

    private void resetBeforeCapture(ChromeDriver driver) throws InterruptedException {
        driver.manage().window().setSize(new Dimension(CAPTURE_WINDOW_WIDTH, 900));
        ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
        TimeUnit.MILLISECONDS.sleep(100);
    }

    private boolean isDriverAlive(WebDriver driver) {
        if (driver == null) {
            return false;
        }
        try {
            driver.getTitle();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDriverSessionInvalid(Exception e) {
        if (!(e instanceof org.openqa.selenium.WebDriverException)) {
            return false;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("invalid session")
                || lower.contains("not reachable")
                || lower.contains("disconnected")
                || lower.contains("no such window")
                || lower.contains("chrome not reachable");
    }

    // 캡처 파일 이름 생성
    public static String formatFilename(int excelRowNumber, String postNo) {
        return String.format("연번 %03d_post_%s.png", excelRowNumber, postNo);
    }

    // URL에서 게시글 번호 추출
    public static String extractPostNoFromUrl(String url) {
        Matcher matcher = POST_NO_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("URL에서 게시글 번호(no)를 찾을 수 없습니다: " + url);
    }

    private void setupChromeDriver(String binary) {
        String majorVersion = detectChromeMajorVersion(binary);
        WebDriverManager manager = WebDriverManager.chromedriver();
        if (!majorVersion.isEmpty()) {
            manager.browserVersion(majorVersion);
            log.info("ChromeDriver browserVersion={}", majorVersion);
        }
        manager.setup();
    }

    // 웹 드라이버 생성
    private ChromeDriver createDriver() {
        ChromeOptions options = new ChromeOptions();
        options.setBinary(chromeBinary);
        options.setPageLoadStrategy(PageLoadStrategy.NONE);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("intl.accept_languages", "ko-KR,ko");
        prefs.put("translate.enabled", false);
        options.setExperimentalOption("prefs", prefs);

        options.addArguments(
                "--headless=new",
                "--disable-gpu",
                "--no-sandbox",
                "--disable-setuid-sandbox",
                "--disable-dev-shm-usage",
                "--disable-software-rasterizer",
                "--disable-extensions",
                "--remote-allow-origins=*",
                "--window-size=1280,900",
                "--lang=ko-KR",
                "--accept-lang=ko-KR,ko"
        );
        options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        ChromeDriverService service = new ChromeDriverService.Builder()
                .withEnvironment(Map.of("LANG", "ko_KR.UTF-8", "LC_ALL", "ko_KR.UTF-8"))
                .build();
        return new ChromeDriver(service, options);
    }

    // 웹 드라이버 종료
    private void quitDriver(WebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (Exception e) {
            log.debug("WebDriver quit failed: {}", e.getMessage());
        }
    }

    // 한글 폰트 미설치 경고
    private void warnIfKoreanFontsMissing() {
        try {
            Process process = new ProcessBuilder("fc-list", ":lang=ko")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            process.waitFor(3, TimeUnit.SECONDS);
            if (output.toString().isBlank()) {
                log.warn(
                        "한글 폰트가 설치되어 있지 않습니다. 캡처 이미지에서 한글이 깨질 수 있습니다. "
                                + "해결: sudo apt install -y fonts-nanum fonts-noto-cjk fontconfig && fc-cache -fv"
                );
            }
        } catch (Exception e) {
            log.debug("Korean font check skipped: {}", e.getMessage());
        }
    }

    // 댓글 대기
    private void waitForCommentsIfNeeded(WebDriver driver, String postNo) throws InterruptedException {
        if (commentWaitMs <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + commentWaitMs;
        String selector = "#comment_wrap_" + postNo + " .comment_box li";
        while (System.currentTimeMillis() < deadline) {
            if (!driver.findElements(By.cssSelector(selector)).isEmpty()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
    }

    // 캡처 대상 찾기
    private WebElement findCaptureTarget(WebDriver driver, WebDriverWait wait) {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(CAPTURE_TARGET_CSS)));
        } catch (TimeoutException e) {
            WebElement fallback = (WebElement) ((JavascriptExecutor) driver).executeScript(
                    "const head = document.querySelector('div.wrap_inner .gallview_head');"
                            + "return head ? head.closest('article') : null;"
            );
            if (fallback == null) {
                throw new IllegalStateException("캡처 영역(본문 article)을 찾을 수 없습니다.", e);
            }
            return fallback;
        }
    }

    // 본문 + 댓글 전체 페이지 캡처 (댓글 페이지네이션 포함, 열 단위 즉시 병합)
    private byte[] captureWithCommentPages(ChromeDriver driver, WebElement captureTarget, String postNo)
            throws InterruptedException, IOException {
        StepTimer timer = new StepTimer(log, "capture-images post=" + postNo);
        List<Integer> commentPages = detectCommentPages(driver, postNo);
        timer.step("detect-comment-pages (" + commentPages.size() + " pages)");
        if (!captureAllCommentPages || commentPages.size() <= 1) {
            byte[] capture = captureRegionScreenshot(driver, captureTarget, postNo);
            timer.step("capture-main-region (" + capture.length + " bytes)");
            timer.done();
            return capture;
        }

        List<byte[]> completedColumns = new ArrayList<>();
        byte[] currentColumn = captureRegionScreenshot(driver, captureTarget, postNo);
        timer.step("capture-main-region (" + currentColumn.length + " bytes)");
        int capturedPageCount = 1;

        for (int page : commentPages) {
            if (page == 1) {
                continue;
            }
            goToCommentPage(driver, postNo, page);
            waitForCommentPage(driver, postNo, page);
            byte[] pageCapture = captureCommentPageScreenshot(driver, postNo);
            timer.step("comment-page-" + page + " (" + pageCapture.length + " bytes)");
            capturedPageCount++;

            int currentHeight = getImageHeight(currentColumn);
            int pageHeight = getImageHeight(pageCapture);
            if (currentHeight + pageHeight > MAX_STITCHED_HEIGHT) {
                completedColumns.add(currentColumn);
                currentColumn = pageCapture;
            } else {
                currentColumn = appendVertically(currentColumn, pageCapture);
            }
        }
        completedColumns.add(currentColumn);

        log.info(
                "댓글 {}페이지 캡처 후 {}열로 이미지 병합 (총 {}장)",
                commentPages.size(),
                completedColumns.size(),
                capturedPageCount
        );

        byte[] result;
        if (completedColumns.size() == 1) {
            result = completedColumns.get(0);
            timer.step("merge-images (single column)");
        } else {
            result = stitchHorizontally(completedColumns);
            timer.step("merge-images (horizontal stitch, " + completedColumns.size() + " columns)");
        }
        timer.done();
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> detectCommentPages(WebDriver driver, String postNo) {
        List<Long> pages = (List<Long>) ((JavascriptExecutor) driver).executeScript(
                """
                const postNo = arguments[0];
                const paging = document.querySelector('#comment_wrap_' + postNo + ' .cmt_paging');
                if (!paging) return [1];

                const pageSet = new Set();
                paging.querySelectorAll('a, em, span, strong').forEach((el) => {
                  const text = (el.textContent || '').trim();
                  const num = parseInt(text, 10);
                  if (!Number.isNaN(num) && num > 0) {
                    pageSet.add(num);
                  }
                  const action = (el.getAttribute('href') || '') + (el.getAttribute('onclick') || '');
                  const match = action.match(/viewComments\\s*\\(\\s*(\\d+)/);
                  if (match) {
                    pageSet.add(parseInt(match[1], 10));
                  }
                });

                if (pageSet.size === 0) return [1];
                return Array.from(pageSet).sort((a, b) => a - b);
                """,
                postNo
        );
        if (pages == null || pages.isEmpty()) {
            return List.of(1);
        }

        List<Integer> result = new ArrayList<>();
        for (Long page : pages) {
            if (page != null && page > 0 && page <= MAX_COMMENT_PAGES) {
                result.add(page.intValue());
            }
        }
        return result.isEmpty() ? List.of(1) : result;
    }

    private void goToCommentPage(WebDriver driver, String postNo, int page) {
        ((JavascriptExecutor) driver).executeScript(
                """
                const postNo = arguments[0];
                const page = arguments[1];
                const wrap = document.getElementById('comment_wrap_' + postNo);
                let sort = (wrap && wrap.getAttribute('data-sort-type')) || 'D';
                const checked = wrap && wrap.querySelector('input[name=selCommentSort]:checked');
                if (checked && checked.value) {
                  sort = checked.value;
                }
                if (typeof viewComments === 'function') {
                  viewComments(page, sort, false);
                } else {
                  throw new Error('viewComments 함수를 찾을 수 없습니다.');
                }
                """,
                postNo,
                page
        );
    }

    private void waitForCommentPage(WebDriver driver, String postNo, int expectedPage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(commentWaitMs, 3000L);
        String activePageSelector = "#comment_wrap_" + postNo + " .cmt_paging em";
        while (System.currentTimeMillis() < deadline) {
            List<WebElement> activePages = driver.findElements(By.cssSelector(activePageSelector));
            if (!activePages.isEmpty()) {
                String current = activePages.get(0).getText().trim();
                if (current.equals(String.valueOf(expectedPage))) {
                    TimeUnit.MILLISECONDS.sleep(400);
                    return;
                }
            }
            TimeUnit.MILLISECONDS.sleep(200);
        }
        log.warn("댓글 {}페이지 로딩 대기 시간 초과", expectedPage);
        TimeUnit.MILLISECONDS.sleep(500);
    }

    private byte[] captureCommentPageScreenshot(ChromeDriver driver, String postNo)
            throws InterruptedException {
        WebElement commentBox = driver.findElement(By.cssSelector(String.format(COMMENT_BOX_CSS, postNo)));
        prepareWindowForCommentPageCapture(driver, commentBox, postNo);
        Map<String, Object> clip = resolveCommentPageClipBounds(driver, postNo);
        validateClipBounds(clip);
        return captureClip(driver, clip);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveCommentPageClipBounds(ChromeDriver driver, String postNo) {
        Map<String, Object> clip = (Map<String, Object>) ((JavascriptExecutor) driver).executeScript(
                """
                const postNo = arguments[0];
                const commentBox = document.querySelector('#comment_wrap_' + postNo + ' .comment_box');
                const cmtPaging = document.querySelector('#comment_wrap_' + postNo + ' .cmt_paging');
                if (!commentBox) return null;

                const scrollX = window.pageXOffset || document.documentElement.scrollLeft || 0;
                const scrollY = window.pageYOffset || document.documentElement.scrollTop || 0;
                const boxRect = commentBox.getBoundingClientRect();

                let maxBottom = boxRect.bottom + scrollY;
                if (cmtPaging) {
                  const pagingRect = cmtPaging.getBoundingClientRect();
                  maxBottom = Math.max(maxBottom, pagingRect.bottom + scrollY);
                }

                const top = boxRect.top + scrollY;
                return {
                  x: Math.max(0, Math.floor(boxRect.left + scrollX)),
                  y: Math.max(0, Math.floor(top)),
                  width: Math.max(1, Math.ceil(boxRect.width)),
                  height: Math.max(1, Math.ceil(maxBottom - top)),
                  scale: 1
                };
                """,
                postNo
        );
        if (clip == null) {
            throw new IllegalStateException("댓글 캡처 영역(.comment_box ~ .cmt_paging)을 찾을 수 없습니다.");
        }
        return clip;
    }

    private void prepareWindowForCommentPageCapture(ChromeDriver driver, WebElement commentBox, String postNo)
            throws InterruptedException {
        JavascriptExecutor js = driver;
        driver.manage().window().setSize(new Dimension(CAPTURE_WINDOW_WIDTH, 900));
        js.executeScript("window.scrollTo(0, 0);");
        TimeUnit.MILLISECONDS.sleep(100);
        js.executeScript("arguments[0].scrollIntoView({block: 'start'});", commentBox);
        TimeUnit.MILLISECONDS.sleep(200);

        Map<String, Object> clip = resolveCommentPageClipBounds(driver, postNo);
        int clipHeight = ((Number) clip.get("height")).intValue();
        int captureHeight = (int) Math.min(Math.max(clipHeight + 80, 400), MAX_CAPTURE_HEIGHT);
        driver.manage().window().setSize(new Dimension(CAPTURE_WINDOW_WIDTH, captureHeight));
        js.executeScript("arguments[0].scrollIntoView({block: 'start'});", commentBox);
        TimeUnit.MILLISECONDS.sleep(300);
    }

    // 캡처 영역 스크린샷
    private byte[] captureRegionScreenshot(ChromeDriver driver, WebElement captureTarget, String postNo)
            throws InterruptedException {
        prepareWindowForCapture(driver, captureTarget);
        Map<String, Object> clip = resolveCaptureClipBounds(driver, captureTarget, postNo);
        validateClipBounds(clip);
        return captureClip(driver, clip);
    }

    private byte[] captureClip(ChromeDriver driver, Map<String, Object> clip) {
        Map<String, Object> params = new HashMap<>();
        params.put("clip", clip);
        params.put("captureBeyondViewport", true);
        params.put("fromSurface", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = driver.executeCdpCommand("Page.captureScreenshot", params);
        String base64 = (String) result.get("data");
        return Base64.getDecoder().decode(base64);
    }

    private BufferedImage readBufferedImage(byte[] pngBytes) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IOException("PNG 이미지를 읽을 수 없습니다.");
            }
            return image;
        }
    }

    private int getImageHeight(byte[] pngBytes) throws IOException {
        return readBufferedImage(pngBytes).getHeight();
    }

    private byte[] appendVertically(byte[] topImage, byte[] bottomImage) throws IOException {
        BufferedImage top = readBufferedImage(topImage);
        BufferedImage bottom = readBufferedImage(bottomImage);
        int totalWidth = Math.max(top.getWidth(), bottom.getWidth());
        int totalHeight = top.getHeight() + bottom.getHeight();

        BufferedImage combined = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = combined.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, totalWidth, totalHeight);
        graphics.drawImage(top, 0, 0, null);
        graphics.drawImage(bottom, 0, top.getHeight(), null);
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(combined, "png", output);
        return output.toByteArray();
    }

    private byte[] stitchHorizontally(List<byte[]> columnImages) throws IOException {
        List<BufferedImage> images = new ArrayList<>();
        int totalWidth = 0;
        int totalHeight = 0;
        for (byte[] columnImage : columnImages) {
            BufferedImage image = readBufferedImage(columnImage);
            images.add(image);
            totalWidth += image.getWidth();
            totalHeight = Math.max(totalHeight, image.getHeight());
        }

        BufferedImage combined = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = combined.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, totalWidth, totalHeight);

        int x = 0;
        for (BufferedImage image : images) {
            graphics.drawImage(image, x, 0, null);
            x += image.getWidth();
        }
        graphics.dispose();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(combined, "png", output);
        return output.toByteArray();
    }

    // 캡처 영역 좌표 계산
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveCaptureClipBounds(
            ChromeDriver driver,
            WebElement captureTarget,
            String postNo
    ) {
        // 캡처 영역 좌표 계산
        Map<String, Object> clip = (Map<String, Object>) ((JavascriptExecutor) driver).executeScript(
                """
                const article = arguments[0];
                const postNo = arguments[1];
                const scrollX = window.pageXOffset || document.documentElement.scrollLeft || 0;
                const scrollY = window.pageYOffset || document.documentElement.scrollTop || 0;
                let minTop = Infinity;
                let minLeft = Infinity;
                let maxBottom = 0;
                let maxRight = 0;

                function include(el) {
                  if (!el) return;
                  const rect = el.getBoundingClientRect();
                  if (rect.width <= 0 && rect.height <= 0) return;
                  minTop = Math.min(minTop, rect.top + scrollY);
                  minLeft = Math.min(minLeft, rect.left + scrollX);
                  maxBottom = Math.max(maxBottom, rect.bottom + scrollY);
                  maxRight = Math.max(maxRight, rect.right + scrollX);
                }

                include(article);
                [
                  '.gallview_head',
                  '.gallview_contents',
                  '.view_comment',
                  '#comment_wrap_' + postNo,
                  '.cmt_write_box'
                ].forEach((selector) => article.querySelectorAll(selector).forEach(include));

                if (!Number.isFinite(minTop)) {
                  return null;
                }

                return {
                  x: Math.max(0, Math.floor(minLeft)),
                  y: Math.max(0, Math.floor(minTop)),
                  width: Math.max(1, Math.ceil(maxRight - minLeft)),
                  height: Math.max(1, Math.ceil(maxBottom - minTop)),
                  scale: 1
                };
                """,
                captureTarget,
                postNo
        );
        if (clip == null) {
            throw new IllegalStateException("캡처 영역 좌표를 계산할 수 없습니다.");
        }
        return clip;
    }

    // 캡처 영역 크기 검증
    private void validateClipBounds(Map<String, Object> clip) {
        int width = ((Number) clip.get("width")).intValue();
        int height = ((Number) clip.get("height")).intValue();
        if (width < 100 || height < 100) {
            throw new IllegalStateException(
                    "캡처 영역이 너무 작습니다. (width=" + width + ", height=" + height + ")"
            );
        }
    }

    // 캡처 영역 윈도우 준비
    private void prepareWindowForCapture(ChromeDriver driver, WebElement captureTarget) throws InterruptedException {
        JavascriptExecutor js = driver;
        driver.manage().window().setSize(new Dimension(CAPTURE_WINDOW_WIDTH, 900));
        js.executeScript("window.scrollTo(0, 0);");
        TimeUnit.MILLISECONDS.sleep(200);

        long contentHeight = ((Number) js.executeScript(
                "return Math.max(arguments[0].scrollHeight, arguments[0].getBoundingClientRect().height);",
                captureTarget
        )).longValue();

        int captureHeight = (int) Math.min(Math.max(contentHeight + 120, 900), MAX_CAPTURE_HEIGHT);
        driver.manage().window().setSize(new Dimension(CAPTURE_WINDOW_WIDTH, captureHeight));
        js.executeScript("arguments[0].scrollIntoView({block: 'start'});", captureTarget);
        TimeUnit.MILLISECONDS.sleep(300);
    }

    // Chrome 실행 파일 경로 탐지
    private String resolveChromeBinary() {
        if (!configuredChromeBinary.isEmpty()) {
            Path path = Paths.get(configuredChromeBinary);
            if (!Files.isExecutable(path)) {
                throw new IllegalStateException("설정한 Chrome 경로를 실행할 수 없습니다: " + configuredChromeBinary);
            }
            return path.toAbsolutePath().toString();
        }

        List<String> candidates = List.of(
                "/usr/bin/google-chrome-stable",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser"
        );

        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isExecutable(path)) {
                return path.toAbsolutePath().toString();
            }
        }

        throw new IllegalStateException(
                "Chrome/Chromium 실행 파일을 찾을 수 없습니다. "
                        + "Google Chrome 또는 chromium-browser를 설치하거나 "
                        + "evidence.chrome.binary 설정값을 지정하세요."
        );
    }

    // Chrome 버전 감지
    private String detectChromeMajorVersion(String binary) {
        try {
            Process process = new ProcessBuilder(binary, "--version").redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                Matcher matcher = CHROME_VERSION_PATTERN.matcher(output.toString());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            log.warn("Chrome version detection failed: {}", e.getMessage());
        }
        return "";
    }
}
