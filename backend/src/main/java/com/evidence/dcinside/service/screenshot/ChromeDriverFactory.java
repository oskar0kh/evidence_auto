package com.evidence.dcinside.service.screenshot;

import com.evidence.dcinside.DcinsideConstants;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChromeDriverFactory {

    private static final Logger log = LoggerFactory.getLogger(ChromeDriverFactory.class);
    private static final Pattern CHROME_VERSION_PATTERN = Pattern.compile("(\\d+)\\.");

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
        String majorVersion = detectChromeMajorVersion(chromeBinary);
        WebDriverManager manager = WebDriverManager.chromedriver();
        if (!majorVersion.isEmpty()) {
            manager.browserVersion(majorVersion);
            log.info("ChromeDriver browserVersion={}", majorVersion);
        }
        manager.setup();
    }

    public static ChromeDriver createDriver(String chromeBinary) {
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
        options.addArguments("--user-agent=" + DcinsideConstants.USER_AGENT);

        ChromeDriverService service = new ChromeDriverService.Builder()
                .withEnvironment(Map.of("LANG", "ko_KR.UTF-8", "LC_ALL", "ko_KR.UTF-8"))
                .build();
        return new ChromeDriver(service, options);
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
                || lower.contains("chrome not reachable");
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

    private static String detectChromeMajorVersion(String binary) {
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
