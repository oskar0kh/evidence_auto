package com.evidence.dcinside.service.screenshot;

import com.evidence.dcinside.DcinsideConstants;
import com.evidence.dcinside.DcinsideUserAgent;
import com.evidence.dcinside.service.CrawlTelemetryBridge;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChromeDriverFactory {

    private static final Logger log = LoggerFactory.getLogger(ChromeDriverFactory.class);
    private static final Pattern CHROME_VERSION_PATTERN = Pattern.compile("(\\d+)\\.");
    private static final Pattern CHROME_FULL_VERSION_PATTERN =
            Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)");

    private ChromeDriverFactory() {
    }

    public static String resolveChromeBinary(String configuredChromeBinary) {
        String configured = configuredChromeBinary == null ? "" : configuredChromeBinary.trim();
        if (!configured.isEmpty()) {
            Path path = Paths.get(configured);
            if (!Files.isExecutable(path)) {
                throw new IllegalStateException("설정한 Chrome 경로를 실행할 수 없습니다: " + configured);
            }
            return path.toAbsolutePath().toString();
        }

        List<String> candidates = List.of(
                ".chrome/opt/google/chrome/chrome",
                "/usr/bin/google-chrome-stable",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser"
        );

        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (!path.isAbsolute()) {
                path = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
            }
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

    public static void setupChromeDriver(String chromeBinary) {
        String versionOutput = readChromeVersionOutput(chromeBinary);
        DcinsideUserAgent.syncFromChromeVersionOutput(versionOutput);
        String fullVersion = detectChromeFullVersion(versionOutput);
        String majorVersion = detectChromeMajorVersion(versionOutput);

        WebDriverManager manager = WebDriverManager.chromedriver();

        String driverVersion = !fullVersion.isBlank() ? fullVersion : majorVersion;
        if (!driverVersion.isBlank()) {
            manager.browserVersion(driverVersion);
            log.info("ChromeDriver browserVersion={} (binary={})", driverVersion, chromeBinary);
        }

        manager.setup();
        pinChromeDriverToBrowserVersion(fullVersion, majorVersion);
        log.info(
                "ChromeDriver ready: {} | Chrome {} | User-Agent: {}",
                System.getProperty("webdriver.chrome.driver", manager.getDownloadedDriverPath()),
                fullVersion.isBlank() ? majorVersion : fullVersion,
                DcinsideConstants.userAgent()
        );
    }

    /**
     * WebDriverManager가 시스템 Chrome(예: 151)용 드라이버를 고르는 경우가 있어,
     * 실제 바이너리 버전과 일치하는 캐시 chromedriver로 고정한다.
     */
    private static void pinChromeDriverToBrowserVersion(String fullVersion, String majorVersion) {
        resolveCachedChromeDriver(fullVersion, majorVersion).ifPresent(driverPath -> {
            System.setProperty("webdriver.chrome.driver", driverPath);
            log.info("ChromeDriver pinned to {}", driverPath);
        });
    }

    private static Optional<String> resolveCachedChromeDriver(String fullVersion, String majorVersion) {
        Path root = Path.of(System.getProperty("user.home"), ".cache/selenium/chromedriver/linux64");
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        if (!fullVersion.isBlank()) {
            Path exact = root.resolve(fullVersion).resolve("chromedriver");
            if (Files.isExecutable(exact)) {
                return Optional.of(exact.toAbsolutePath().toString());
            }
        }
        if (majorVersion.isBlank()) {
            return Optional.empty();
        }
        try (var entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().startsWith(majorVersion + "."))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .map(dir -> dir.resolve("chromedriver"))
                    .filter(Files::isExecutable)
                    .map(path -> path.toAbsolutePath().toString())
                    .findFirst();
        } catch (Exception e) {
            log.debug("Cached chromedriver lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static ChromeDriver createDriver(String chromeBinary) {
        return createDriver(chromeBinary, 30);
    }

    public static boolean isDriverStartFailure(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof TimeoutException) {
            return true;
        }
        String message = error.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("chromedriver 시작")
                    || lower.contains("chromedriver 시작 시간 초과")
                    || lower.contains("could not start a new session")
                    || lower.contains("chrome failed to start")
                    || lower.contains("chrome not reachable")
                    || lower.contains("unable to create new service")
                    || lower.contains("session not created")) {
                return true;
            }
        }
        return error.getCause() != null && isDriverStartFailure(error.getCause());
    }

    public static void cleanupStaleChromeProcesses() {
        runQuietProcess("pkill", "-f", "chromedriver");
        runQuietProcess("pkill", "-f", "chrome.*--headless=new");
    }

    public static ChromeDriver createDriverWithRecovery(
            String chromeBinary,
            int startTimeoutSeconds,
            int maxAttempts,
            long recoveryDelayMs
    ) throws Exception {
        int attempts = Math.max(1, maxAttempts);
        Exception lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return createDriver(chromeBinary, startTimeoutSeconds);
            } catch (Exception e) {
                lastError = e;
                if (!isDriverStartFailure(e) || attempt >= attempts) {
                    throw e;
                }
                log.warn(
                        "ChromeDriver 시작 실패 ({}/{}): {} — 프로세스 정리 후 재시도",
                        attempt,
                        attempts,
                        e.getMessage()
                );
                CrawlTelemetryBridge.record(
                        "ChromeDriver 복구 재시도 " + attempt + "/" + attempts + ": " + e.getMessage()
                );
                cleanupStaleChromeProcesses();
                if (recoveryDelayMs > 0) {
                    Thread.sleep(recoveryDelayMs);
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("ChromeDriver 시작 실패");
    }

    public static ChromeDriver createDriver(String chromeBinary, int startTimeoutSeconds) {
        return createDriver(chromeBinary, startTimeoutSeconds, true, null);
    }

    /**
     * Instagram 로그인 헬퍼용: 창이 보이는 Chrome + 프로필 디렉터리.
     * 한 번 로그인하면 같은 user-data-dir에서 세션이 유지된다.
     */
    public static ChromeDriver createHeadedDriver(String chromeBinary, Path userDataDir) {
        return createDriver(chromeBinary, 60, false, userDataDir);
    }

    public static ChromeDriver createDriver(
            String chromeBinary,
            int startTimeoutSeconds,
            boolean headless,
            Path userDataDir
    ) {
        ChromeOptions options = new ChromeOptions();
        options.setBinary(chromeBinary);
        options.setPageLoadStrategy(PageLoadStrategy.NONE);

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("intl.accept_languages", "ko-KR,ko");
        prefs.put("translate.enabled", false);
        options.setExperimentalOption("prefs", prefs);

        if (headless) {
            options.addArguments("--headless=new");
        }
        options.addArguments(
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
        if (userDataDir != null) {
            try {
                Files.createDirectories(userDataDir);
            } catch (Exception e) {
                throw new IllegalStateException("Chrome 프로필 디렉터리 생성 실패: " + userDataDir, e);
            }
            options.addArguments("--user-data-dir=" + userDataDir.toAbsolutePath());
        }
        options.addArguments("--user-agent=" + DcinsideConstants.userAgent());

        // Selenium withEnvironment는 프로세스 env를 통째로 교체하므로 기존 env를 복사한다.
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("LANG", "ko_KR.UTF-8");
        env.put("LC_ALL", "ko_KR.UTF-8");
        String display = env.get("DISPLAY");
        if (!headless && (display == null || display.isBlank())) {
            // WSLg 기본 디스플레이
            env.put("DISPLAY", ":0");
        }

        ChromeDriverService service = new ChromeDriverService.Builder()
                .withEnvironment(env)
                .build();

        int timeout = Math.max(5, startTimeoutSeconds);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ChromeDriver> future = executor.submit(() -> new ChromeDriver(service, options));
        try {
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            cleanupStaleChromeProcesses();
            throw new IllegalStateException("ChromeDriver 시작 시간 초과 (" + timeout + "초)", e);
        } catch (Exception e) {
            cleanupStaleChromeProcesses();
            throw new IllegalStateException("ChromeDriver 시작 실패: " + e.getMessage(), e);
        } finally {
            executor.shutdownNow();
        }
    }

    public static void quitDriver(WebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (Exception e) {
            log.debug("WebDriver quit failed: {}", e.getMessage());
        }
    }

    public static boolean isDriverAlive(WebDriver driver) {
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

    public static boolean isDriverSessionInvalid(Exception e) {
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
                || lower.contains("chrome not reachable")
                || lower.contains("could not start a new session");
    }

    public static void warnIfKoreanFontsMissing() {
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

    private static String readChromeVersionOutput(String binary) {
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
                return output.toString();
            }
        } catch (Exception e) {
            log.warn("Chrome version detection failed: {}", e.getMessage());
        }
        return "";
    }

    private static String detectChromeMajorVersion(String versionOutput) {
        Matcher matcher = CHROME_VERSION_PATTERN.matcher(versionOutput == null ? "" : versionOutput);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    static String detectChromeFullVersion(String versionOutput) {
        Matcher matcher = CHROME_FULL_VERSION_PATTERN.matcher(versionOutput == null ? "" : versionOutput);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static void runQuietProcess(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Process cleanup skipped ({}): {}", String.join(" ", command), e.getMessage());
        }
    }
}
