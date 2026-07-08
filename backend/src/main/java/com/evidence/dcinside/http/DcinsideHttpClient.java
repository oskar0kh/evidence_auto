package com.evidence.dcinside.http;

import com.evidence.dcinside.DcinsideConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

@Service
public class DcinsideHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DcinsideHttpClient.class);

    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient httpClient;
    private final int maxAttempts;
    private final long backoffBaseMs;

    public DcinsideHttpClient(
            @Value("${evidence.crawl.retry-max-attempts:3}") int maxAttempts,
            @Value("${evidence.crawl.retry-backoff-base-ms:1000}") long backoffBaseMs
    ) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffBaseMs = Math.max(0, backoffBaseMs);
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public HttpResponse<String> get(String url) throws Exception {
        return get(url, null, "document");
    }

    public HttpResponse<String> get(String url, String referer) throws Exception {
        return get(url, referer, "document");
    }

    public HttpResponse<String> get(String url, String referer, String fetchDest) throws Exception {
        return get(url, referer, fetchDest, true);
    }

    public HttpResponse<String> get(String url, String referer, String fetchDest, boolean resilient) throws Exception {
        return executeWithRetry(
                request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
                builder -> {
                    builder.uri(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .GET();
                    applyBrowserHeaders(builder, referer, fetchDest);
                },
                resilient ? maxAttempts : 1
        );
    }

    public HttpResponse<String> postForm(String url, String formBody, String referer) throws Exception {
        return postForm(url, formBody, referer, true);
    }

    public HttpResponse<String> postForm(String url, String formBody, String referer, boolean resilient) throws Exception {
        return executeWithRetry(
                request -> httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)),
                builder -> {
                    builder.uri(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(formBody));
                    applyBrowserHeaders(builder, referer, "empty");
                    builder.header("Origin", "https://gall.dcinside.com");
                    builder.header("X-Requested-With", "XMLHttpRequest");
                    builder.header("Accept", "application/json, text/javascript, */*; q=0.01");
                },
                resilient ? maxAttempts : 1
        );
    }

    public Optional<HttpResponse<String>> getOptional(String url, String referer) {
        try {
            return Optional.of(get(url, referer));
        } catch (Exception e) {
            log.warn("HTTP GET failed for {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    public void resetCookies() {
        cookieManager.getCookieStore().removeAll();
    }

    public void importCookies(Map<String, String> cookies, String domain) {
        if (cookies == null || cookies.isEmpty()) {
            return;
        }
        URI uri = URI.create("https://" + domain);
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            HttpCookie cookie = new HttpCookie(entry.getKey(), entry.getValue());
            cookie.setDomain(domain);
            cookie.setPath("/");
            cookieManager.getCookieStore().add(uri, cookie);
        }
    }

    private HttpResponse<String> executeWithRetry(
            ThrowingFunction<HttpRequest, HttpResponse<String>> sender,
            Consumer<HttpRequest.Builder> requestConfigurer,
            int attempts
    ) throws Exception {
        int effectiveAttempts = Math.max(1, attempts);
        Exception lastError = null;
        for (int attempt = 1; attempt <= effectiveAttempts; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder();
                requestConfigurer.accept(builder);
                HttpResponse<String> response = sender.apply(builder.build());
                BlockSignal signal = BlockSignal.detect(response.statusCode(), response.body());
                if (signal != null && signal.isRetryable() && attempt < effectiveAttempts) {
                    log.warn(
                            "Retryable block signal {} on attempt {}/{} (HTTP {})",
                            signal,
                            attempt,
                            effectiveAttempts,
                            response.statusCode()
                    );
                    sleepBackoff(attempt);
                    continue;
                }
                if (signal != null) {
                    throw new IllegalStateException(buildFailureMessage(response.statusCode(), signal));
                }
                return response;
            } catch (IllegalStateException e) {
                lastError = e;
                if (attempt < effectiveAttempts) {
                    sleepBackoff(attempt);
                }
            } catch (Exception e) {
                lastError = e;
                if (attempt < effectiveAttempts) {
                    log.warn("HTTP request failed on attempt {}/{}: {}", attempt, effectiveAttempts, e.getMessage());
                    sleepBackoff(attempt);
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("HTTP 요청에 실패했습니다.");
    }

    private void sleepBackoff(int attempt) throws InterruptedException {
        if (backoffBaseMs <= 0) {
            return;
        }
        long delay = backoffBaseMs * (1L << Math.min(attempt - 1, 4));
        int jitter = ThreadLocalRandom.current().nextInt(0, (int) Math.max(1, delay / 4));
        Thread.sleep(delay + jitter);
    }

    private static String buildFailureMessage(int statusCode, BlockSignal signal) {
        if (signal == BlockSignal.BOT_CHALLENGE) {
            return "페이지를 불러올 수 없습니다. 봇 차단이 감지되었습니다.";
        }
        return "페이지를 불러올 수 없습니다. HTTP " + statusCode;
    }

    private static void applyBrowserHeaders(HttpRequest.Builder builder, String referer, String fetchDest) {
        builder.header("User-Agent", DcinsideConstants.userAgent());
        builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        builder.header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
        builder.header("Cache-Control", "no-cache");
        builder.header("Pragma", "no-cache");
        builder.header("Upgrade-Insecure-Requests", "1");
        builder.header("Sec-Fetch-Dest", fetchDest);
        builder.header("Sec-Fetch-Mode", "navigate".equals(fetchDest) ? "navigate" : "cors");
        builder.header("Sec-Fetch-Site", referer == null || referer.isBlank() ? "none" : "same-origin");
        if ("navigate".equals(fetchDest)) {
            builder.header("Sec-Fetch-User", "?1");
        }
        if (referer != null && !referer.isBlank()) {
            builder.header("Referer", referer);
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws Exception;
    }
}
