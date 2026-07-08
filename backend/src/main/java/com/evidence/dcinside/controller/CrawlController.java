package com.evidence.dcinside.controller;

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
import com.evidence.dcinside.http.CrawlThrottle;
import com.evidence.dcinside.http.DcinsideHttpClient;
import com.evidence.dcinside.service.DcinsideCrawlService;
import com.evidence.dcinside.service.DcinsideSearchService;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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

    public CrawlController(
            DcinsideCrawlService crawlService,
            DcinsideSearchService searchService,
            ScreenshotService screenshotService,
            DcinsideFetchEscalator fetchEscalator,
            DcinsideHttpClient httpClient,
            CrawlThrottle crawlThrottle
    ) {
        this.crawlService = crawlService;
        this.searchService = searchService;
        this.screenshotService = screenshotService;
        this.fetchEscalator = fetchEscalator;
        this.httpClient = httpClient;
        this.crawlThrottle = crawlThrottle;
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
        httpClient.resetCookies();

        try (ScreenshotService.CaptureSession captureSession = screenshotService.openCaptureSession()) {
            for (String trimmed : validUrls) {
                sleepBeforeRequestQuietly(health);
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
        httpClient.resetCookies();

        try (ScreenshotService.CaptureSession captureSession = screenshotService.openCaptureSession()) {
            searchService.forEachSearchUrl(
                    criteria,
                    seen,
                    pageEvent -> emitSearchProgress(pageEvent, state, callbacks),
                    url -> {
                        state.total = Math.max(state.total, seen.size());
                        sleepBeforeRequestQuietly(health);
                        processSingleUrl(url, state, captureSession, callbacks, health);
                        return true;
                    },
                    (searchUrl, message) -> {
                        health.activateProtectiveMode();
                        callbacks.urlError().accept(errorEntry(searchUrl, message, "search"));
                        emitHealth(callbacks, health, message);
                    },
                    health
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
            ScreenshotService.CaptureSession captureSession,
            CrawlCallbacks callbacks,
            CrawlHealthTracker health
    ) {
        callbacks.progress().accept(new CrawlProgressEvent(
                state.completed, state.total, trimmed, "text-crawl", state.successCount, state.failCount
        ));

        StepTimer timer = new StepTimer(log, "crawl-url " + trimmed);
        List<StepTimings> partialTimings = new ArrayList<>();

        try {
            captureSession.setRelaxBlockTracking(health.shouldRelaxBlockTracking());
            FetchedPage fetchedPage = fetchEscalator.fetchPostPage(trimmed, health, captureSession);
            emitHealth(callbacks, health, null);

            TimedResult<DcinsidePostData> crawled = crawlService.crawl(trimmed, fetchedPage, health);
            partialTimings.add(crawled.timings());
            timer.step("text-crawl");

            callbacks.progress().accept(new CrawlProgressEvent(
                    state.completed, state.total, trimmed, "screenshot", state.successCount, state.failCount
            ));

            int excelRowNumber = state.nextSerial++;
            String postNo = ScreenshotService.extractPostNoFromUrl(trimmed);
            boolean skipNavigate = fetchedPage.phase() == FetchPhase.BROWSER;
            TimedResult<CaptureImage> capture = captureSession.captureFullPage(
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
        } catch (StageTimedException e) {
            partialTimings.add(e.timings());
            log.warn("Crawl failed for {} at {}: {}", trimmed, e.stage(), e.getMessage());
            health.recordFailure(BlockSignal.HTTP_ERROR, health.currentPhase());
            Map<String, String> error = errorEntry(trimmed, e);
            UrlTiming failedTiming = buildFailedTiming(trimmed, partialTimings, timer);
            callbacks.urlError().accept(error);
            callbacks.urlTiming().accept(failedTiming);
            emitHealth(callbacks, health, e.getMessage());
            state.failCount++;
            state.completed++;
            callbacks.progress().accept(new CrawlProgressEvent(
                    state.completed, state.total, trimmed, "url-failed", state.successCount, state.failCount
            ));
        } catch (Exception e) {
            log.warn("Crawl failed for {}: {}", trimmed, e.getMessage());
            health.recordFailure(BlockSignal.HTTP_ERROR, health.currentPhase());
            Map<String, String> error = errorEntry(trimmed, e);
            UrlTiming failedTiming = buildFailedTiming(trimmed, partialTimings, timer);
            callbacks.urlError().accept(error);
            callbacks.urlTiming().accept(failedTiming);
            emitHealth(callbacks, health, e.getMessage());
            state.failCount++;
            state.completed++;
            callbacks.progress().accept(new CrawlProgressEvent(
                    state.completed, state.total, trimmed, "url-failed", state.successCount, state.failCount
            ));
        }
    }

    private void emitHealth(CrawlCallbacks callbacks, CrawlHealthTracker health, String message) {
        callbacks.health().accept(health.snapshot(message));
    }

    private void sleepBeforeRequestQuietly(CrawlHealthTracker health) {
        try {
            crawlThrottle.sleepBeforeRequest(health.isProtectiveMode());
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
        Map<String, String> entry = new HashMap<>();
        entry.put("url", url);
        entry.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        if (e instanceof StageTimedException stageTimed) {
            entry.put("stage", stageTimed.stage());
        }
        return entry;
    }

    private static Map<String, String> errorEntry(String url, String message, String stage) {
        Map<String, String> entry = new HashMap<>();
        entry.put("url", url);
        entry.put("error", message);
        entry.put("stage", stage);
        return entry;
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
