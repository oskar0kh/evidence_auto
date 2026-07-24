package com.evidence.instagram.controller;

import com.evidence.dcinside.dto.CaptureImage;
import com.evidence.dcinside.dto.CrawlProgressEvent;
import com.evidence.dcinside.dto.TimedResult;
import com.evidence.dcinside.dto.UrlTiming;
import com.evidence.dcinside.util.StepTimer;
import com.evidence.dcinside.util.StepTimings;
import com.evidence.instagram.dto.InstagramCrawlRequest;
import com.evidence.instagram.dto.InstagramPostData;
import com.evidence.instagram.dto.InstagramSearchCrawlRequest;
import com.evidence.instagram.http.InstagramHttpClient;
import com.evidence.instagram.model.InstagramParsedPost;
import com.evidence.instagram.service.InstagramCrawlService;
import com.evidence.instagram.service.InstagramScreenshotService;
import com.evidence.instagram.util.InstagramUrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/crawl/instagram")
public class InstagramCrawlController {

    private static final Logger log = LoggerFactory.getLogger(InstagramCrawlController.class);

    private final InstagramCrawlService crawlService;
    private final InstagramScreenshotService screenshotService;
    private final InstagramHttpClient httpClient;
    private final boolean parallelScreenshot;

    public InstagramCrawlController(
            InstagramCrawlService crawlService,
            InstagramScreenshotService screenshotService,
            InstagramHttpClient httpClient,
            @Value("${evidence.instagram.parallel-screenshot:true}") boolean parallelScreenshot
    ) {
        this.crawlService = crawlService;
        this.screenshotService = screenshotService;
        this.httpClient = httpClient;
        this.parallelScreenshot = parallelScreenshot;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter crawlStream(@RequestBody InstagramCrawlRequest request) {
        if (request.urls() == null || request.urls().isEmpty()) {
            SseEmitter emitter = new SseEmitter(-1L);
            sendErrorAndComplete(emitter, "URL을 입력해 주세요.");
            return emitter;
        }
        return runSse(callbacks -> crawlAllUrls(request, callbacks));
    }

    @PostMapping(value = "/search-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter searchCrawlStream(@RequestBody InstagramSearchCrawlRequest request) {
        SseEmitter emitter = new SseEmitter(-1L);
        sendErrorAndComplete(emitter, "인스타그램 검색 크롤링은 아직 구현되지 않았습니다. URL 직접입력을 사용해 주세요.");
        return emitter;
    }

    private SseEmitter runSse(CrawlJob job) {
        SseEmitter emitter = new SseEmitter(-1L);
        CompletableFuture.runAsync(() -> {
            try {
                CrawlCallbacks callbacks = CrawlCallbacks.streaming(this, emitter);
                CrawlSummary summary = job.run(callbacks);
                sendEvent(emitter, "complete", summary.toBody());
                emitter.complete();
            } catch (Exception e) {
                log.error("Instagram SSE crawl failed", e);
                sendErrorAndComplete(
                        emitter,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                );
            }
        });
        emitter.onTimeout(() -> log.warn("Instagram SSE crawl timed out"));
        emitter.onError(error -> log.warn("Instagram SSE crawl error: {}", error.getMessage()));
        return emitter;
    }

    private CrawlSummary crawlAllUrls(InstagramCrawlRequest request, CrawlCallbacks callbacks) throws Exception {
        List<String> urls = request.urls().stream()
                .map(url -> url == null ? "" : url.trim())
                .filter(url -> !url.isEmpty())
                .filter(InstagramUrlUtils::isInstagramPostUrl)
                .toList();

        CrawlState state = new CrawlState(urls.size(), request.startSerial());
        String searchQuery = request.searchQuery();

        if (screenshotService.isEnabled()) {
            try (InstagramScreenshotService.CaptureSession session = screenshotService.openSession()) {
                for (String url : urls) {
                    processUrl(url, searchQuery, state, callbacks, session);
                }
            }
        } else {
            for (String url : urls) {
                processUrl(url, searchQuery, state, callbacks, null);
            }
        }
        return state.toSummary();
    }

    private void processUrl(
            String url,
            String searchQuery,
            CrawlState state,
            CrawlCallbacks callbacks,
            InstagramScreenshotService.CaptureSession captureSession
    ) {
        callbacks.progress.accept(state.progress(url, "fetch"));
        StepTimer timer = new StepTimer(log, "instagram-crawl " + url);
        List<StepTimings> partialTimings = new ArrayList<>();
        try {
            if (captureSession != null && parallelScreenshot) {
                processUrlParallel(url, searchQuery, state, callbacks, captureSession, timer, partialTimings);
            } else {
                processUrlSequential(url, searchQuery, state, callbacks, captureSession, timer, partialTimings);
            }
        } catch (Exception e) {
            log.warn("Instagram URL crawl failed: {} — {}", url, e.getMessage());
            callbacks.urlTiming.accept(buildTiming(url, false, timer, partialTimings));
            state.failCount++;
            state.completed++;
            callbacks.urlError.accept(errorEntry(url, e));
            callbacks.progress.accept(state.progress(url, "url-error"));
        }
    }

    private void processUrlSequential(
            String url,
            String searchQuery,
            CrawlState state,
            CrawlCallbacks callbacks,
            InstagramScreenshotService.CaptureSession captureSession,
            StepTimer timer,
            List<StepTimings> partialTimings
    ) throws Exception {
        TimedResult<InstagramParsedPost> fetched = crawlService.fetchParsedPostTimed(url);
        partialTimings.add(fetched.timings());
        timer.step("text-crawl");

        InstagramParsedPost parsed = fetched.value();
        List<InstagramPostData> rows = crawlService.buildRows(parsed, searchQuery);
        timer.step("build-result");

        if (!rows.isEmpty() && captureSession != null) {
            callbacks.progress.accept(state.progress(url, "screenshot"));
            int serial = state.nextSerial();
            TimedResult<CaptureImage> capture = captureSession.capturePostTimed(
                    parsed.url(),
                    serial,
                    parsed.shortcode()
            );
            partialTimings.add(capture.timings());
            timer.step("screenshot");
            rows = crawlService.attachCapture(rows, capture.value());
            timer.step("attach-capture");
        }

        emitSuccess(url, rows, state, callbacks, timer, partialTimings);
    }

    private void processUrlParallel(
            String url,
            String searchQuery,
            CrawlState state,
            CrawlCallbacks callbacks,
            InstagramScreenshotService.CaptureSession captureSession,
            StepTimer timer,
            List<StepTimings> partialTimings
    ) throws Exception {
        TimedResult<InstagramParsedPost> meta = crawlService.fetchPostMetaTimed(url);
        partialTimings.add(meta.timings());
        InstagramParsedPost parsed = meta.value();
        httpClient.reloadSessionCookiesFromFile();

        callbacks.progress.accept(state.progress(url, "screenshot"));
        int serial = state.nextSerial();

        CompletableFuture<TimedResult<InstagramParsedPost>> commentsFuture = CompletableFuture.supplyAsync(
                () -> crawlService.collectCommentsTimed(parsed)
        );

        TimedResult<CaptureImage> capture;
        try {
            capture = captureSession.capturePostTimed(parsed.url(), serial, parsed.shortcode());
            partialTimings.add(capture.timings());
            timer.step("screenshot");
        } catch (Exception captureError) {
            commentsFuture.cancel(true);
            throw captureError;
        }

        TimedResult<InstagramParsedPost> withComments;
        try {
            withComments = commentsFuture.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
        partialTimings.add(withComments.timings());
        timer.step("text-crawl");

        List<InstagramPostData> rows = crawlService.buildRows(withComments.value(), searchQuery);
        timer.step("build-result");
        rows = crawlService.attachCapture(rows, capture.value());
        timer.step("attach-capture");

        emitSuccess(url, rows, state, callbacks, timer, partialTimings);
    }

    private void emitSuccess(
            String url,
            List<InstagramPostData> rows,
            CrawlState state,
            CrawlCallbacks callbacks,
            StepTimer timer,
            List<StepTimings> partialTimings
    ) {
        UrlTiming successTiming = buildTiming(url, true, timer, partialTimings);
        callbacks.urlTiming.accept(successTiming);

        for (InstagramPostData row : rows) {
            callbacks.urlResult.accept(row);
            state.successCount++;
        }
        if (rows.isEmpty()) {
            state.failCount++;
            callbacks.urlError.accept(errorEntry(url, new IllegalStateException("파싱 결과가 비어 있습니다.")));
        }
        state.completed++;
        callbacks.progress.accept(state.progress(url, "url-done"));
    }

    private static UrlTiming buildTiming(
            String url,
            boolean success,
            StepTimer timer,
            List<StepTimings> partialTimings
    ) {
        StepTimings outer = timer.finish();
        StepTimings mergedInner = partialTimings.isEmpty()
                ? new StepTimings("empty", Map.of(), 0)
                : StepTimings.merge(partialTimings.toArray(StepTimings[]::new));
        Map<String, Long> steps = new LinkedHashMap<>(mergedInner.normalizedSteps());
        outer.normalizedSteps().forEach((name, ms) -> steps.merge(name, ms, Long::sum));
        return new UrlTiming(url, success, outer.totalMs(), Map.copyOf(steps));
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 전송 실패: " + e.getMessage(), e);
        }
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("error", message), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private static Map<String, Object> errorEntry(String url, Exception e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("url", url);
        map.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        map.put("stage", "fetch");
        return map;
    }

    @FunctionalInterface
    private interface CrawlJob {
        CrawlSummary run(CrawlCallbacks callbacks) throws Exception;
    }

    private record CrawlCallbacks(
            Consumer<CrawlProgressEvent> progress,
            Consumer<InstagramPostData> urlResult,
            Consumer<Map<String, Object>> urlError,
            Consumer<UrlTiming> urlTiming
    ) {
        static CrawlCallbacks streaming(InstagramCrawlController controller, SseEmitter emitter) {
            return new CrawlCallbacks(
                    event -> controller.sendEvent(emitter, "progress", event),
                    data -> controller.sendEvent(emitter, "url-result", data),
                    error -> controller.sendEvent(emitter, "url-error", error),
                    timing -> controller.sendEvent(emitter, "url-timing", timing)
            );
        }
    }

    private static final class CrawlState {
        private final int total;
        private final int startSerial;
        private int nextSerial;
        private int completed;
        private int successCount;
        private int failCount;

        private CrawlState(int total, Integer startSerial) {
            this.total = total;
            this.startSerial = startSerial != null && startSerial > 0 ? startSerial : 1;
            this.nextSerial = this.startSerial;
        }

        private int nextSerial() {
            return nextSerial++;
        }

        private CrawlProgressEvent progress(String currentUrl, String stage) {
            return new CrawlProgressEvent(completed, total, currentUrl, stage, successCount, failCount);
        }

        private CrawlSummary toSummary() {
            return new CrawlSummary(successCount, failCount, completed, startSerial);
        }
    }

    private record CrawlSummary(int successCount, int failCount, int attemptedCount, int startSerial) {
        Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("successCount", successCount);
            body.put("failCount", failCount);
            body.put("attemptedCount", attemptedCount);
            body.put("startSerial", startSerial);
            body.put("data", new ArrayList<>());
            body.put("errors", new ArrayList<>());
            return body;
        }
    }
}
