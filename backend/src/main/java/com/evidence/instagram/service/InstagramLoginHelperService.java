package com.evidence.instagram.service;

import com.evidence.config.AppPaths;
import com.evidence.dcinside.service.screenshot.ChromeDriverFactory;
import com.evidence.instagram.http.InstagramHttpClient;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.chrome.ChromeDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instagram 로그인 헬퍼.
 * <p>
 * 화면이 보이는 Chrome을 열고 사용자가 로그인한 뒤 {@code sessionid} 쿠키가 생기면
 * 쿠키 파일에 저장하고 {@link InstagramHttpClient}에 즉시 반영한다.
 */
@Service
public class InstagramLoginHelperService {

    private static final Logger log = LoggerFactory.getLogger(InstagramLoginHelperService.class);
    private static final String LOGIN_URL = "https://www.instagram.com/accounts/login/";
    private static final String HOME_URL = "https://www.instagram.com/";

    public enum Phase {
        IDLE,
        STARTING,
        WAITING_LOGIN,
        SAVING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    public record SessionStatus(
            Phase phase,
            boolean loggedIn,
            boolean loginInProgress,
            String message,
            String cookiesFile,
            String profileDir,
            Instant updatedAt
    ) {
    }

    private final InstagramHttpClient httpClient;
    private final String chromeBinaryConfigured;
    private final Path profileDir;
    private final long timeoutSeconds;
    private final long pollIntervalMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.IDLE);
    private final AtomicReference<String> message = new AtomicReference<>("대기 중");
    private final AtomicReference<Instant> updatedAt = new AtomicReference<>(Instant.now());
    private final AtomicReference<ChromeDriver> activeDriver = new AtomicReference<>();

    public InstagramLoginHelperService(
            InstagramHttpClient httpClient,
            AppPaths appPaths,
            @Value("${evidence.chrome.binary:}") String chromeBinaryConfigured,
            @Value("${evidence.instagram.login.profile-dir:instagram-chrome-profile}") String profileDir,
            @Value("${evidence.instagram.login.timeout-seconds:300}") long timeoutSeconds,
            @Value("${evidence.instagram.login.poll-interval-ms:1500}") long pollIntervalMs
    ) {
        this.httpClient = httpClient;
        this.chromeBinaryConfigured = chromeBinaryConfigured;
        this.profileDir = appPaths.resolve(profileDir);
        this.timeoutSeconds = Math.max(30, timeoutSeconds);
        this.pollIntervalMs = Math.max(500, pollIntervalMs);
    }

    public SessionStatus status() {
        return new SessionStatus(
                phase.get(),
                httpClient.hasLoginSession(),
                running.get(),
                message.get(),
                httpClient.cookiesFile().toString(),
                profileDir.toString(),
                updatedAt.get()
        );
    }

    public synchronized Map<String, Object> startLogin() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!running.compareAndSet(false, true)) {
            result.put("started", false);
            result.put("status", status());
            result.put("message", "이미 로그인 헬퍼가 실행 중입니다. Chrome 창에서 로그인해 주세요.");
            return result;
        }
        cancelRequested.set(false);
        setPhase(Phase.STARTING, "Chrome을 여는 중…");
        CompletableFuture.runAsync(this::runLoginFlow);
        result.put("started", true);
        result.put("status", status());
        result.put("message", "Chrome 창이 열리면 Instagram에 로그인해 주세요. 완료되면 자동으로 쿠키가 저장됩니다.");
        return result;
    }

    public Map<String, Object> cancelLogin() {
        cancelRequested.set(true);
        ChromeDriver driver = activeDriver.get();
        if (driver != null) {
            ChromeDriverFactory.quitDriver(driver);
            activeDriver.compareAndSet(driver, null);
        }
        if (running.get()) {
            setPhase(Phase.CANCELLED, "로그인 헬퍼가 취소되었습니다.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cancelled", true);
        result.put("status", status());
        return result;
    }

    private void runLoginFlow() {
        ChromeDriver driver = null;
        try {
            Files.createDirectories(profileDir);
            String chromeBinary = ChromeDriverFactory.resolveChromeBinary(chromeBinaryConfigured);
            ChromeDriverFactory.setupChromeDriver(chromeBinary);
            setPhase(Phase.STARTING, "Chrome 시작 중… (" + chromeBinary + ")");
            driver = ChromeDriverFactory.createHeadedDriver(chromeBinary, profileDir);
            activeDriver.set(driver);

            driver.get(LOGIN_URL);
            setPhase(
                    Phase.WAITING_LOGIN,
                    "Chrome에서 Instagram에 로그인해 주세요. (최대 " + timeoutSeconds + "초)"
            );

            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                if (cancelRequested.get()) {
                    setPhase(Phase.CANCELLED, "로그인 헬퍼가 취소되었습니다.");
                    return;
                }
                if (!ChromeDriverFactory.isDriverAlive(driver)) {
                    setPhase(Phase.FAILED, "Chrome 창이 닫혔습니다. 다시 시도해 주세요.");
                    return;
                }

                String cookieHeader = extractSessionCookieHeader(driver);
                if (cookieHeader != null && cookieHeader.contains("sessionid=")) {
                    setPhase(Phase.SAVING, "로그인 감지 — 쿠키 저장 중…");
                    httpClient.applyAndPersistSessionCookies(cookieHeader);
                    // 홈으로 한 번 이동해 mid 등 부가 쿠키도 보강
                    try {
                        driver.get(HOME_URL);
                        Thread.sleep(1500);
                        String enriched = extractSessionCookieHeader(driver);
                        if (enriched != null && enriched.contains("sessionid=")) {
                            httpClient.applyAndPersistSessionCookies(enriched);
                        }
                    } catch (Exception enrichError) {
                        log.debug("Cookie enrichment skipped: {}", enrichError.getMessage());
                    }
                    setPhase(Phase.SUCCESS, "로그인 세션이 저장되었습니다. 이제 댓글 크롤링을 사용할 수 있습니다.");
                    return;
                }
                Thread.sleep(pollIntervalMs);
            }
            setPhase(Phase.FAILED, "로그인 대기 시간이 초과되었습니다. 다시 시도해 주세요.");
        } catch (Exception e) {
            log.warn("Instagram login helper failed: {}", e.getMessage(), e);
            String hint = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (hint.toLowerCase().contains("display") || hint.toLowerCase().contains("cannot open display")) {
                hint += " — WSL에서는 WSLg(DISPLAY)가 필요합니다.";
            }
            setPhase(Phase.FAILED, "로그인 헬퍼 실패: " + hint);
        } finally {
            ChromeDriver current = activeDriver.getAndSet(null);
            if (current != null) {
                ChromeDriverFactory.quitDriver(current);
            } else if (driver != null) {
                ChromeDriverFactory.quitDriver(driver);
            }
            running.set(false);
            if (phase.get() == Phase.STARTING || phase.get() == Phase.WAITING_LOGIN || phase.get() == Phase.SAVING) {
                setPhase(Phase.FAILED, "로그인 헬퍼가 예기치 않게 종료되었습니다.");
            }
        }
    }

    private String extractSessionCookieHeader(ChromeDriver driver) {
        try {
            Set<Cookie> cookies = driver.manage().getCookies();
            String header = InstagramHttpClient.buildCookieHeaderFromSelenium(cookies);
            if (header.contains("sessionid=")) {
                return header;
            }
            // domain filter miss 대비: 전체 쿠키에서 sessionid만 직접 찾기
            for (Cookie cookie : cookies) {
                if ("sessionid".equals(cookie.getName())
                        && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return InstagramHttpClient.buildCookieHeaderFromSelenium(cookies);
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("Cookie read failed: {}", e.getMessage());
            return null;
        }
    }

    private void setPhase(Phase next, String nextMessage) {
        phase.set(next);
        message.set(nextMessage);
        updatedAt.set(Instant.now());
        log.info("Instagram login helper: {} — {}", next, nextMessage);
    }
}
