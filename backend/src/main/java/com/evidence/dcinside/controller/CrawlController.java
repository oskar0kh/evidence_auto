package com.evidence.dcinside.controller;

import com.evidence.dcinside.CrawlDeadline;
import com.evidence.dcinside.dto.CrawlHealthEvent;
import com.evidence.dcinside.dto.CrawlRequest;
import com.evidence.dcinside.dto.DcinsidePostData;
import com.evidence.dcinside.dto.SearchCrawlRequest;
import com.evidence.dcinside.dto.SearchPageEvent;
import com.evidence.dcinside.dto.SearchStreamCriteria;
import com.evidence.dcinside.fetch.CrawlHealthTracker;
import com.evidence.dcinside.fetch.DcinsideFetchEscalator;
import com.evidence.dcinside.fetch.FetchedPage;
import com.evidence.dcinside.fetch.FetchPhase;
import com.evidence.dcinside.http.BlockSignal;
import com.evidence.dcinside.http.ConnectionFailureDetector;
import com.evidence.dcinside.http.CrawlThrottle;
import com.evidence.dcinside.http.DcinsideHttpClient;
import com.evidence.dcinside.service.DcinsideCrawlService;
import com.evidence.dcinside.service.DcinsideSearchService;
import com.evidence.dcinside.service.RotatingCaptureSession;
import com.evidence.dcinside.service.ScreenshotService;
import com.evidence.dto.CaptureImage;
import com.evidence.dto.CrawlProgressEvent;
import com.evidence.dto.TimedResult;
import com.evidence.dto.UrlTiming;
import com.evidence.exception.StageTimedException;
import com.evidence.util.StepTimer;
import com.evidence.util.StepTimings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

@RestController
@RequestMapping("/api/crawl")
public class CrawlController {

    private static final Logger log = LoggerFactory.getLogger(CrawlController.class);
    private final DcinsideCrawlService crawlService;
    private final DcinsideSearchService searchService;
    private final ScreenshotService screenshotService;
    private final DcinsideFetchEscalator fetchEscalator;
    private final DcinsideHttpClient httpClient;
    private final CrawlThrottle crawlThrottle;
    private final int urlRetryMaxAttempts;
    private final int urlProtectiveRetryMaxAttempts;
    private final long urlMaxDurationMs;
    private final int chromeSessionRotateEveryUrls;

    public CrawlController(
            DcinsideCrawlService crawlService,
            DcinsideSearchService searchService,
            ScreenshotService screenshotService,
            DcinsideFetchEscalator fetchEscalator,
            DcinsideHttpClient httpClient,
            CrawlThrottle crawlThrottle,
            @Value("${evidence.crawl.url-retry-max-attempts:2}") int urlRetryMaxAttempts,
            @Value("${evidence.crawl.url-protective-retry-max-attempts:2}") int urlProtectiveRetryMaxAttempts,
            @Value("${evidence.crawl.url-max-duration-ms:180000}") long urlMaxDurationMs,
            @Value("${evidence.crawl.chrome-session-rotate-every-urls:25}") int chromeSessionRotateEveryUrls
    ) {
        this.crawlService = crawlService;
        this.searchService = searchService;
        this.screenshotService = screenshotService;
        this.fetchEscalator = fetchEscalator;
        this.httpClient = httpClient;
        this.crawlThrottle = crawlThrottle;
        this.urlRetryMaxAttempts = Math.max(1, urlRetryMaxAttempts);
        this.urlProtectiveRetryMaxAttempts = Math.max(1, urlProtectiveRetryMaxAttempts);
        this.urlMaxDurationMs = Math.max(0, urlMaxDurationMs);
        this.chromeSessionRotateEveryUrls = Math.max(0, chromeSessionRotateEveryUrls);
    }

    @PostMapping(value = "/dcinside/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter crawlDcinsideStream(@RequestBody CrawlRequest request) {
        if (request.urls() == null || request.urls().isEmpty()) {
            SseEmitter emitter = new SseEmitter(-1L);
            sendErrorAndComplete(emitter, "URL을 입력해 주세요.");
            return emitter;
        }
        return runSseCrawl(callbacks -> crawlAllUrls(request, callbacks));
    }

    @PostMapping(value = "/dcinside/search-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter searchCrawlDcinsideStream(@RequestBody SearchCrawlRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            SseEmitter emitter = new SseEmitter(-1L);
            sendErrorAndComplete(emitter, "검색어를 입력해 주세요.");
            return emitter;
        }
        return runSseCrawl(callbacks -> searchAndCrawl(request, callbacks));
    }

    private SseEmitter runSseCrawl(Function<CrawlCallbacks, CrawlSummary> job) {
        SseEmitter emitter = new SseEmitter(-1L);

        CompletableFuture.runAsync(() -> {
            try {
                CrawlCallbacks callbacks = CrawlCallbacks.streaming(this, emitter);
                CrawlSummary summary = job.apply(callbacks);
                sendEvent(emitter, "complete", summary.toBody());
                emitter.complete();
            } catch (Exception e) {
                log.error("SSE crawl stream failed", e);
                sendErrorAndComplete(
                        emitter,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                );
                try {
                    sendEvent(emitter, "url-error", errorEntry("(크롤 세션/스트림)", e));
                } catch (Exception sendFailure) {
                    log.warn("Failed to emit session url-error after stream failure", sendFailure);
                }
            }
        });

        emitter.onTimeout(() -> log.warn("SSE crawl stream timed out"));
        emitter.onError(error -> log.warn("SSE crawl stream error: {}", error.getMessage()));

        return emitter;
    }

    private CrawlSummary crawlAllUrls(CrawlRequest request, CrawlCallbacks callbacks) {
        List<String> validUrls = request.urls().stream()
                .map(url -> url == null ? "" : url.trim())
                .filter(url -> !url.isEmpty())
                .toList();
        if (DcinsideSearchService.hasGalleryId(request.galleryId())) {
            validUrls = DcinsideSearchService.filterUrlsByGalleryId(validUrls, request.galleryId());
        }

        CrawlState state = new CrawlState(validUrls.size(), request.startSerial());
        CrawlHealthTracker health = fetchEscalator.newHealthTracker();
        prepareCrawlEnvironment();

        try (RotatingCaptureSession captureSession = openRotatingSession()) {
            for (String trimmed : validUrls) {
                sleepBeforeRequestQuietly(health, state.completed);
                processSingleUrl(trimmed, state, captureSession, callbacks, health);
            }
        } catch (Exception e) {
            throw new IllegalStateException("크롤 세션을 종료할 수 없습니다: " + e.getMessage(), e);
        }

        return state.toSummary();
    }

    private CrawlSummary searchAndCrawl(SearchCrawlRequest request, CrawlCallbacks callbacks) {
        LocalDate startDate = DcinsideSearchService.parseRequestDate(request.startDate());
        LocalDate endDate = DcinsideSearchService.parseRequestDate(request.endDate());
        SearchStreamCriteria criteria = new SearchStreamCriteria(
                request.query(),
                request.maxResults(),
                startDate,
                endDate,
                request.galleryId()
        );

        Set<String> seen = new LinkedHashSet<>();
        CrawlState state = new CrawlState(0, request.startSerial());
        CrawlHealthTracker health = fetchEscalator.newHealthTracker();
        prepareCrawlEnvironment();

        try (RotatingCaptureSession captureSession = openRotatingSession()) {
            searchService.forEachSearchUrl(
                    criteria,
                    seen,
                    pageEvent -> emitSearchProgress(pageEvent, state, callbacks),
                    url -> {
                        state.total = Math.max(state.total, seen.size());
                        sleepBeforeRequestQuietly(health, state.completed);
                        processSingleUrl(url, state, captureSession, callbacks, health);
                        return true;
                    },
                    (searchUrl, message) -> {
                        health.activateProtectiveMode();
                        callbacks.urlError().accept(errorEntry(searchUrl, message, "search"));
                        emitHealth(callbacks, health, message);
                    },
                    health,
                    () -> state.completed
            );
        } catch (Exception e) {
            throw new IllegalStateException("검색 크롤 세션 오류: " + e.getMessage(), e);
        }

        state.total = Math.max(state.total, state.completed);
        return state.toSummary();
    }

    private void emitSearchProgress(SearchPageEvent pageEvent, CrawlState state, CrawlCallbacks callbacks) {
        String label = String.format(
                "검색어 %d/%d: \"%s\" (페이지 %d, 발견 %d건)",
                pageEvent.termIndex(),
                pageEvent.termTotal(),
                pageEvent.term(),
                pageEvent.page(),
                pageEvent.discoveredCount()
        );
        callbacks.progress().accept(new CrawlProgressEvent(
                state.completed,
                state.total,
                label,
                "search",
                state.successCount,
                state.failCount
        ));
    }

    private void processSingleUrl(
            String trimmed,
            CrawlState state,
            RotatingCaptureSession captureSession,
            CrawlCallbacks callbacks,
            CrawlHealthTracker health
    ) {
        callbacks.progress().accept(new CrawlProgressEvent(
                state.completed, state.total, trimmed, "text-crawl", state.successCount, state.failCount
        ));

        CrawlDeadline deadline = CrawlDeadline.fromNow(urlMaxDurationMs);
        boolean wasProtective = health.isProtectiveMode();
        Exception lastFailure = null;
        List<StepTimings> lastPartialTimings = new ArrayList<>();
        StepTimer lastTimer = new StepTimer(log, "crawl-url " + trimmed);

        try {
            for (int attempt = 1; attempt <= urlRetryMaxAttempts; attempt++) {
                if (isUrlBudgetExceeded(deadline)) {
                    lastFailure = new TimeoutException("URL 처리 시간 예산을 초과했습니다.");
                    break;
                }
                if (attempt > 1) {
                    sleepBeforeRequestQuietly(health, state.completed);
                    emitHealth(callbacks, health, "Fast Mode 재시도 " + attempt + "/" + urlRetryMaxAttempts);
                }
                try {
                    tryProcessUrlOnce(trimmed, state, captureSession, callbacks, health, deadline);
                    health.recordSuccess(health.currentPhase());
                    if (wasProtective && !health.isProtectiveMode()) {
                        emitHealth(callbacks, health, "보호 모드 해제 → Fast Mode 복귀");
                    } else {
                        emitHealth(callbacks, health, null);
                    }
                    return;
                } catch (StageTimedException e) {
                    lastFailure = e;
                    lastPartialTimings = new ArrayList<>(List.of(e.timings()));
                    lastTimer = new StepTimer(log, "crawl-url " + trimmed);
                    log.warn("Fast crawl attempt {}/{} failed for {} at {}: {}",
                            attempt, urlRetryMaxAttempts, trimmed, e.stage(), e.getMessage());
                } catch (Exception e) {
                    lastFailure = e;
                    lastPartialTimings = new ArrayList<>();
                    lastTimer = new StepTimer(log, "crawl-url " + trimmed);
                    log.warn("Fast crawl attempt {}/{} failed for {}: {}",
                            attempt, urlRetryMaxAttempts, trimmed, e.getMessage());
                }
            }

            if (!isUrlBudgetExceeded(deadline)) {
                health.activateProtectiveMode();
                emitHealth(callbacks, health, "Fast Mode 실패 → 보호 모드로 재시도");

                for (int attempt = 1; attempt <= urlProtectiveRetryMaxAttempts; attempt++) {
                    if (isUrlBudgetExceeded(deadline)) {
                        lastFailure = new TimeoutException("URL 처리 시간 예산을 초과했습니다.");
                        break;
                    }
                    sleepBeforeRequestQuietly(health, state.completed);
                    emitHealth(callbacks, health, "보호 모드 재시도 " + attempt + "/" + urlProtectiveRetryMaxAttempts);
                    try {
                        tryProcessUrlOnce(trimmed, state, captureSession, callbacks, health, deadline);
                        health.recordSuccess(health.currentPhase());
                        if (!health.isProtectiveMode()) {
                            emitHealth(callbacks, health, "보호 모드 해제 → Fast Mode 복귀");
                        } else {
                            emitHealth(callbacks, health, null);
                        }
                        return;
                    } catch (StageTimedException e) {
                        lastFailure = e;
                        lastPartialTimings = new ArrayList<>(List.of(e.timings()));
                        lastTimer = new StepTimer(log, "crawl-url " + trimmed);
                        log.warn("Protective crawl attempt {}/{} failed for {} at {}: {}",
                                attempt, urlProtectiveRetryMaxAttempts, trimmed, e.stage(), e.getMessage());
                    } catch (Exception e) {
                        lastFailure = e;
                        lastPartialTimings = new ArrayList<>();
                        lastTimer = new StepTimer(log, "crawl-url " + trimmed);
                        log.warn("Protective crawl attempt {}/{} failed for {}: {}",
                                attempt, urlProtectiveRetryMaxAttempts, trimmed, e.getMessage());
                    }
                }
            }

            health.recordFailure(BlockSignal.HTTP_ERROR, health.currentPhase());
            Map<String, String> error = lastFailure instanceof StageTimedException stageTimed
                    ? errorEntry(trimmed, stageTimed)
                    : errorEntry(trimmed, lastFailure);
            UrlTiming failedTiming = buildFailedTiming(trimmed, lastPartialTimings, lastTimer);
            callbacks.urlError().accept(error);
            callbacks.urlTiming().accept(failedTiming);
            emitHealth(callbacks, health, lastFailure != null ? lastFailure.getMessage() : "크롤 실패");
            state.failCount++;
            state.completed++;
            callbacks.progress().accept(new CrawlProgressEvent(
                    state.completed, state.total, trimmed, "url-failed", state.successCount, state.failCount
            ));
        } finally {
            captureSession.afterUrlProcessed();
        }
    }

    private void tryProcessUrlOnce(
            String trimmed,
            CrawlState state,
            RotatingCaptureSession captureSession,
            CrawlCallbacks callbacks,
            CrawlHealthTracker health,
            CrawlDeadline deadline
    ) throws Exception {
        StepTimer timer = new StepTimer(log, "crawl-url " + trimmed);
        List<StepTimings> partialTimings = new ArrayList<>();
        ScreenshotService.CaptureSession browserSession = captureSession.current();

        browserSession.setRelaxBlockTracking(health.shouldRelaxBlockTracking());
        FetchedPage fetchedPage = fetchEscalator.fetchPostPage(trimmed, health, browserSession, deadline);

        TimedResult<DcinsidePostData> crawled = crawlService.crawl(trimmed, fetchedPage, health, deadline);
        partialTimings.add(crawled.timings());
        timer.step("text-crawl");

        callbacks.progress().accept(new CrawlProgressEvent(
                state.completed, state.total, trimmed, "screenshot", state.successCount, state.failCount
        ));

        int excelRowNumber = state.nextSerial++;
        String postNo = ScreenshotService.extractPostNoFromUrl(trimmed);
        boolean skipNavigate = fetchedPage.phase() == FetchPhase.BROWSER;
        TimedResult<CaptureImage> capture = browserSession.captureFullPage(
                trimmed,
                excelRowNumber,
                postNo,
                skipNavigate
        );
        partialTimings.add(capture.timings());
        timer.step("screenshot");

        callbacks.progress().accept(new CrawlProgressEvent(
                state.completed, state.total, trimmed, "attach-capture", state.successCount, state.failCount
        ));

        DcinsidePostData attached = crawlService.attachCapture(crawled.value(), capture.value());
        timer.step("attach-capture");

        StepTimings merged = StepTimings.merge(
                partialTimings.toArray(StepTimings[]::new)
        );
        StepTimings withOuter = mergeWithOuter(timer.finish(), merged);
        UrlTiming successTiming = new UrlTiming(
                trimmed, true, withOuter.totalMs(), withOuter.normalizedSteps()
        );
        callbacks.urlResult().accept(attached);
        callbacks.urlTiming().accept(successTiming);

        state.successCount++;
        state.completed++;
        callbacks.progress().accept(new CrawlProgressEvent(
                state.completed, state.total, trimmed, "url-done", state.successCount, state.failCount
        ));
    }

    private void prepareCrawlEnvironment() {
        httpClient.resetCookies();
        httpClient.resetConnectionState();
    }

    private RotatingCaptureSession openRotatingSession() {
        return new RotatingCaptureSession(screenshotService, chromeSessionRotateEveryUrls);
    }

    private static boolean isUrlBudgetExceeded(CrawlDeadline deadline) {
        return deadline.isEnabled() && deadline.isExpired();
    }

    private void emitHealth(CrawlCallbacks callbacks, CrawlHealthTracker health, String message) {
        callbacks.health().accept(health.snapshot(message));
    }

    private void sleepBeforeRequestQuietly(CrawlHealthTracker health, int processedPostUrlCount) {
        try {
            crawlThrottle.sleepBeforeRequest(health.isProtectiveMode(), processedPostUrlCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("크롤링이 중단되었습니다.", e);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException("SSE send failed for event " + eventName, e);
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("error", message), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unexpected crawl API error", e);
        Map<String, String> body = new HashMap<>();
        body.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body);
    }

    private static StepTimings mergeWithOuter(StepTimings outer, StepTimings inner) {
        Map<String, Long> merged = new LinkedHashMap<>(inner.normalizedSteps());
        outer.normalizedSteps().forEach((name, ms) -> merged.merge(name, ms, Long::sum));
        return new StepTimings("crawl-url", Map.copyOf(merged), outer.totalMs());
    }

    private static UrlTiming buildFailedTiming(String url, List<StepTimings> partialTimings, StepTimer timer) {
        partialTimings.add(timer.finish());
        StepTimings merged = StepTimings.merge(partialTimings.toArray(StepTimings[]::new));
        return new UrlTiming(url, false, merged.totalMs(), merged.normalizedSteps());
    }

    private static Map<String, String> errorEntry(String url, Exception e) {
        String stage = resolveFailureStage(e);
        Map<String, String> entry = new HashMap<>();
        entry.put("url", url);
        entry.put("stage", stage);
        entry.put("error", formatFailureMessage(e, stage));
        return entry;
    }

    private static Map<String, String> errorEntry(String url, String message, String stage) {
        Map<String, String> entry = new HashMap<>();
        entry.put("url", url);
        entry.put("stage", stage);
        entry.put("error", formatFailureMessage(message, stage));
        return entry;
    }

    private static String resolveFailureStage(Exception e) {
        if (e instanceof StageTimedException stageTimed) {
            return stageTimed.stage();
        }
        if (e instanceof TimeoutException) {
            return "timeout";
        }
        if (ConnectionFailureDetector.isConnectionFailure(e)) {
            return "connection";
        }
        String message = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
        if (message.contains("could not start a new session")
                || message.contains("chromedriver")
                || message.contains("chrome not reachable")
                || message.contains("스크린샷")) {
            return "screenshot";
        }
        if (message.contains("불러올 수 없습니다")
                || message.contains("봇 차단")
                || message.contains("파싱")
                || message.contains("댓글")) {
            return "text-crawl";
        }
        if (message.contains("검색")) {
            return "search";
        }
        if (message.contains("세션") || message.contains("session")) {
            return "session";
        }
        return "url-failed";
    }

    private static String formatFailureMessage(Exception e, String stage) {
        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return formatFailureMessage(detail, stage);
    }

    private static String formatFailureMessage(String detail, String stage) {
        String label = failureStageLabel(stage);
        if (label.isBlank()) {
            return detail;
        }
        return "[" + label + "] " + detail;
    }

    private static String failureStageLabel(String stage) {
        return switch (stage) {
            case "search" -> "URL 수집";
            case "text-crawl" -> "텍스트 수집";
            case "screenshot" -> "스크린샷";
            case "attach-capture" -> "결과 저장";
            case "timeout" -> "시간 초과";
            case "connection" -> "HTTP 연결";
            case "session" -> "세션/스트림";
            case "url-failed" -> "크롤 실패";
            default -> stage;
        };
    }

    private static final class CrawlState {
        int total;
        int completed;
        int successCount;
        int failCount;
        int nextSerial;

        CrawlState(int total, Integer startSerial) {
            this.total = total;
            this.nextSerial = startSerial != null ? startSerial : 1;
        }

        CrawlSummary toSummary() {
            return new CrawlSummary(successCount, failCount, completed);
        }
    }

    private record CrawlCallbacks(
            Consumer<CrawlProgressEvent> progress,
            Consumer<DcinsidePostData> urlResult,
            Consumer<Map<String, String>> urlError,
            Consumer<UrlTiming> urlTiming,
            Consumer<CrawlHealthEvent> health
    ) {
        static CrawlCallbacks streaming(CrawlController controller, SseEmitter emitter) {
            return new CrawlCallbacks(
                    event -> controller.sendEvent(emitter, "progress", event),
                    data -> controller.sendEvent(emitter, "url-result", data),
                    error -> controller.sendEvent(emitter, "url-error", error),
                    timing -> controller.sendEvent(emitter, "url-timing", timing),
                    health -> controller.sendEvent(emitter, "health", health)
            );
        }
    }

    private record CrawlSummary(
            int successCount,
            int failCount,
            int attemptedCount
    ) {
        Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("successCount", successCount);
            body.put("failCount", failCount);
            body.put("attemptedCount", attemptedCount);
            return body;
        }
    }
}
