package com.evidence.instagram.http;

import com.evidence.config.AppPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Instagram HTTP 클라이언트.
 * <p>
 * 세션 쿠키는 {@link java.net.CookieManager}에 맡기지 않고 직접 {@code Cookie} 헤더로 보낸다.
 * (GraphQL 응답의 Set-Cookie가 CookieManager를 통해 세션을 지우는 문제 방지)
 */
@Component
public class InstagramHttpClient {

    private static final Logger log = LoggerFactory.getLogger(InstagramHttpClient.class);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final Set<String> SESSION_COOKIE_NAMES = Set.of(
            "sessionid", "csrftoken", "ds_user_id", "mid", "ig_did", "rur", "datr"
    );

    private final HttpClient httpClient;
    private final String xIgAppId;
    private final Path cookiesFile;
    private final String inlineCookies;
    private final Map<String, String> sessionCookies = new LinkedHashMap<>();
    private volatile String activeCookieHeader = "";
    private volatile boolean cookiesLoaded;

    public InstagramHttpClient(
            AppPaths appPaths,
            @Value("${evidence.instagram.x-ig-app-id:936619743392459}") String xIgAppId,
            @Value("${evidence.instagram.session-cookies:}") String sessionCookies,
            @Value("${evidence.instagram.session-cookies-file:instagram-session-cookies.txt}") String sessionCookiesFile
    ) {
        this.xIgAppId = xIgAppId;
        this.inlineCookies = sessionCookies != null ? sessionCookies.trim() : "";
        this.cookiesFile = resolveCookiesFile(appPaths, sessionCookiesFile);
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        String initial = resolveInitialCookies();
        if (!initial.isBlank()) {
            replaceInMemoryCookies(initial);
            log.info("Instagram session cookies configured (sessionid present: {})",
                    hasLoginSession());
        } else {
            log.warn("Instagram session cookies not set — use login helper or set session-cookies");
        }
    }

    public Path cookiesFile() {
        return cookiesFile;
    }

    public boolean hasLoginSession() {
        ensureSessionCookies();
        String sessionId = sessionCookies.get("sessionid");
        return sessionId != null && !sessionId.isBlank();
    }

    public Optional<String> csrfToken() {
        ensureSessionCookies();
        return Optional.ofNullable(sessionCookies.get("csrftoken"))
                .filter(v -> !v.isBlank());
    }

    public Optional<String> sessionUserId() {
        ensureSessionCookies();
        return Optional.ofNullable(sessionCookies.get("ds_user_id"))
                .filter(v -> !v.isBlank());
    }

    public synchronized void applyAndPersistSessionCookies(String cookieHeader) throws Exception {
        String normalized = normalizeCookieHeader(cookieHeader);
        if (!normalized.contains("sessionid=")) {
            throw new IllegalArgumentException("sessionid 쿠키가 없습니다.");
        }
        Files.createDirectories(cookiesFile.getParent() != null ? cookiesFile.getParent() : Path.of("."));
        Files.writeString(cookiesFile, normalized + System.lineSeparator(), StandardCharsets.UTF_8);
        replaceInMemoryCookies(normalized);
        log.info("Instagram session cookies saved to {}", cookiesFile.toAbsolutePath());
    }

    public synchronized void reloadSessionCookiesFromFile() {
        cookiesLoaded = false;
        activeCookieHeader = "";
        sessionCookies.clear();
        ensureSessionCookies();
    }

    /** Selenium 캡처 등에 주입할 세션 쿠키 맵. */
    public Map<String, String> exportSessionCookies() {
        ensureSessionCookies();
        return Map.copyOf(sessionCookies);
    }

    public HttpResponse<String> getHtml(String url) throws Exception {
        HttpRequest request = baseRequest(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            throw new IllegalStateException("Instagram HTML 요청 실패: HTTP " + response.statusCode());
        }
        return response;
    }

    /** 로그인 세션으로 Instagram web API v1 JSON을 GET한다. */
    public HttpResponse<String> getApiJson(String referer, String apiUrl) throws Exception {
        HttpRequest.Builder builder = baseRequest(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "*/*")
                .header("X-IG-App-ID", xIgAppId)
                .header("X-ASBD-ID", "198387")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", referer != null ? referer : "https://www.instagram.com/")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .GET();
        csrfToken().ifPresent(token -> builder.header("X-CSRFToken", token));
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            log.warn("Instagram API HTTP {}: {}", response.statusCode(), truncate(response.body()));
            throw new IllegalStateException("Instagram API 요청 실패: HTTP " + response.statusCode());
        }
        return response;
    }

    public HttpResponse<String> postGraphql(String referer, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(e.getValue() != null ? e.getValue() : "", StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest.Builder builder = baseRequest(URI.create("https://www.instagram.com/graphql/query"))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "*/*")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", "https://www.instagram.com")
                .header("Referer", referer != null ? referer : "https://www.instagram.com/")
                .header("X-IG-App-ID", xIgAppId)
                .header("X-ASBD-ID", "198387")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        csrfToken().ifPresent(token -> builder.header("X-CSRFToken", token));
        String friendly = form.get("fb_api_req_friendly_name");
        if (friendly != null && !friendly.isBlank()) {
            builder.header("X-FB-Friendly-Name", friendly);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 400) {
            log.warn("Instagram GraphQL HTTP {}: {}", response.statusCode(), truncate(response.body()));
            throw new IllegalStateException("Instagram GraphQL 요청 실패: HTTP " + response.statusCode());
        }
        return response;
    }

    public Map<String, String> buildLoggedInGraphqlForm(
            ObjectMapper objectMapper,
            String friendlyName,
            String docId,
            Map<String, Object> variables
    ) throws Exception {
        String userId = sessionUserId().orElse("0");
        boolean loggedIn = hasLoginSession();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("av", loggedIn ? userId : "0");
        form.put("__d", "www");
        form.put("__user", userId);
        form.put("__a", "1");
        form.put("fb_api_caller_class", "RelayModern");
        form.put("fb_api_req_friendly_name", friendlyName);
        form.put("variables", objectMapper.writeValueAsString(variables));
        form.put("doc_id", docId);
        form.put("server_timestamps", "true");
        return form;
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        ensureSessionCookies();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
        if (!activeCookieHeader.isBlank()) {
            builder.header("Cookie", activeCookieHeader);
        }
        return builder;
    }

    private synchronized void ensureSessionCookies() {
        if (cookiesLoaded) {
            return;
        }
        String header = resolveInitialCookies();
        if (!header.isBlank()) {
            replaceInMemoryCookies(header);
            return;
        }
        cookiesLoaded = true;
    }

    private void replaceInMemoryCookies(String cookieHeader) {
        sessionCookies.clear();
        for (Map.Entry<String, String> entry : parseCookieHeader(cookieHeader).entrySet()) {
            sessionCookies.put(entry.getKey(), entry.getValue());
        }
        activeCookieHeader = sessionCookies.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
        cookiesLoaded = true;
    }

    static Map<String, String> parseCookieHeader(String cookieHeader) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return parsed;
        }
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String name = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (!name.isEmpty()) {
                parsed.put(name, value);
            }
        }
        return parsed;
    }

    private String resolveInitialCookies() {
        if (!inlineCookies.isBlank()) {
            return normalizeCookieHeader(inlineCookies);
        }
        try {
            if (!Files.isRegularFile(cookiesFile)) {
                return "";
            }
            return normalizeCookieHeader(Files.readString(cookiesFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Failed to read Instagram session cookies file: {}", e.getMessage());
            return "";
        }
    }

    public static String normalizeCookieHeader(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static String buildCookieHeaderFromSelenium(Iterable<org.openqa.selenium.Cookie> cookies) {
        Map<String, String> selected = new LinkedHashMap<>();
        for (org.openqa.selenium.Cookie cookie : cookies) {
            if (cookie == null || cookie.getName() == null) {
                continue;
            }
            String name = cookie.getName();
            if (!SESSION_COOKIE_NAMES.contains(name)) {
                continue;
            }
            if (cookie.getValue() == null || cookie.getValue().isBlank()) {
                continue;
            }
            selected.put(name, cookie.getValue());
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String key : List.of("sessionid", "csrftoken", "ds_user_id", "mid", "ig_did", "rur", "datr")) {
            if (selected.containsKey(key)) {
                ordered.put(key, selected.get(key));
            }
        }
        return ordered.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    public static String stripJsonGuard(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("for (;;);")) {
            return trimmed.substring("for (;;);".length()).trim();
        }
        return trimmed;
    }

    public static boolean isHtmlBody(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        String trimmed = stripJsonGuard(body);
        return trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html");
    }

    public static JsonNode parseGraphqlJson(ObjectMapper objectMapper, String body) throws Exception {
        String json = stripJsonGuard(body);
        if (isHtmlBody(json)) {
            throw new IllegalStateException("Instagram GraphQL이 HTML을 반환했습니다.");
        }
        return objectMapper.readTree(json);
    }

    private static Path resolveCookiesFile(AppPaths appPaths, String sessionCookiesFile) {
        String filePath = sessionCookiesFile != null ? sessionCookiesFile.trim() : "";
        if (filePath.isBlank()) {
            return appPaths.resolve("instagram-session-cookies.txt");
        }
        return appPaths.resolve(filePath);
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    public static Map<String, String> formOf(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("form key/value pair count mismatch");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
