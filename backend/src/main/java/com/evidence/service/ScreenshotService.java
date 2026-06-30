package com.evidence.service;

import com.evidence.dto.CaptureImage;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PostConstruct;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ScreenshotService {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotService.class);

    private static final Pattern POST_NO_PATTERN = Pattern.compile("[?&]no=(\\d+)");
    private static final Pattern CHROME_VERSION_PATTERN = Pattern.compile("(\\d+)\\.");
    private static final int MAX_CAPTURE_WIDTH = 1920;
    private static final int MAX_CAPTURE_HEIGHT = 12000;
    private static final int MAX_CAPTURE_ATTEMPTS = 2;

    private final String configuredChromeBinary;
    private final long commentWaitMs;
    private final int pageLoadTimeoutSeconds;
    private final int contentWaitSeconds;
    private final boolean blockTracking;
    private final Object captureLock = new Object();

    private String chromeBinary;

    // 스크린샷 서비스 생성자
    public ScreenshotService(
            @Value("${evidence.chrome.binary:}") String configuredChromeBinary,
            @Value("${evidence.screenshot.comment-wait-ms:1500}") long commentWaitMs,
            @Value("${evidence.screenshot.page-load-timeout-seconds:15}") int pageLoadTimeoutSeconds,
            @Value("${evidence.screenshot.content-wait-seconds:45}") int contentWaitSeconds,
            @Value("${evidence.screenshot.block-tracking:true}") boolean blockTracking
    ) {
        this.configuredChromeBinary = configuredChromeBinary == null ? "" : configuredChromeBinary.trim();
        this.commentWaitMs = commentWaitMs;
        this.pageLoadTimeoutSeconds = pageLoadTimeoutSeconds;
        this.contentWaitSeconds = contentWaitSeconds;
        this.blockTracking = blockTracking;
    }

    // 스크린샷 서비스 초기화
    @PostConstruct
    void init() throws Exception {
        chromeBinary = resolveChromeBinary();
        setupChromeDriver(chromeBinary);
        warnIfKoreanFontsMissing();
        log.info("Screenshot Chrome binary: {}, blockTracking={}", chromeBinary, blockTracking);
    }

    // 스크린샷 전체 페이지 캡처
    public CaptureImage captureFullPage(String url, int excelRowNumber, String postNo) throws Exception {
        String filename = formatFilename(excelRowNumber, postNo);
        Path tempFile = Files.createTempFile("evidence-capture-", ".png");

        synchronized (captureLock) {
            Exception lastError = null;
            try {
                for (int attempt = 1; attempt <= MAX_CAPTURE_ATTEMPTS; attempt++) {
                    try {
                        captureOnce(url, postNo, tempFile);
                        byte[] pngBytes = Files.readAllBytes(tempFile);
                        return new CaptureImage(filename, pngBytes);
                    } catch (Exception e) {
                        lastError = e;
                        log.warn("Screenshot attempt {}/{} failed for {}: {}", attempt, MAX_CAPTURE_ATTEMPTS, url, e.getMessage());
                        if (attempt < MAX_CAPTURE_ATTEMPTS) {
                            TimeUnit.SECONDS.sleep(1);
                        }
                    }
                }

                throw new IllegalStateException(
                        "스크린샷 캡처 실패: " + (lastError != null && lastError.getMessage() != null
                                ? lastError.getMessage()
                                : "알 수 없는 오류")
                                + " (Chrome: " + chromeBinary + ")",
                        lastError
                );
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    // 스크린샷 한 번 캡처
    private void captureOnce(String url, String postNo, Path filePath) throws Exception {
        
        // 웹 드라이버 초기화
        WebDriver driver = null;
        try {
            ChromeDriver chromeDriver = createDriver(); // 웹 드라이버 생성
            driver = chromeDriver;
            if (blockTracking) {
                TrackingDomainBlocker.apply(chromeDriver); // 광고/트래킹 도메인 차단
            }
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(pageLoadTimeoutSeconds)); // 페이지 로드 타임아웃
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(30)); // 스크립트 타임아웃

            driver.get(url); // 페이지 접속

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(contentWaitSeconds)); // 콘텐츠 대기
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div.gallview_head")));
            waitForCommentsIfNeeded(driver, postNo); // 댓글 대기

            resizeToFullPage(driver); // 전체 페이지 크기 조정

            Path tempFile = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE).toPath();
            Files.copy(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING); // 캡처 파일 복사
        } finally {
            quitDriver(driver); // 웹 드라이버 종료
        }
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

    // 전체 페이지 크기 조정
    private void resizeToFullPage(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        long width = ((Number) js.executeScript(
                "return Math.max(document.body.scrollWidth, document.documentElement.scrollWidth);"
        )).longValue();
        long height = ((Number) js.executeScript(
                "return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);"
        )).longValue();

        int captureWidth = (int) Math.min(Math.max(width, 1280), MAX_CAPTURE_WIDTH);
        int captureHeight = (int) Math.min(Math.max(height, 900), MAX_CAPTURE_HEIGHT);
        driver.manage().window().setSize(new Dimension(captureWidth, captureHeight));
        js.executeScript("window.scrollTo(0, 0);");
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
