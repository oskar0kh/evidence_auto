package com.evidence.dcinside.controller;

import com.evidence.dcinside.dto.CrawlRequest;
import com.evidence.dcinside.dto.DcinsidePostData;
import com.evidence.dcinside.service.DcinsideCrawlService;
import com.evidence.dcinside.service.ScreenshotService;
import com.evidence.dto.CaptureImage;
import com.evidence.dto.CrawlProgressEvent;
import com.evidence.dto.TimedResult;
import com.evidence.dto.UrlTiming;
import com.evidence.service.StageTimedException;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/crawl")
public class CrawlController {

    private static final Logger log = LoggerFactory.getLogger(CrawlController.class);
    private final DcinsideCrawlService crawlService;
    private final ScreenshotService screenshotService;

    public CrawlController(
            DcinsideCrawlService crawlService,
            ScreenshotService screenshotService
    ) {
        this.crawlService = crawlService;
        this.screenshotService = screenshotService;
    }

    @PostMapping(value = "/dcinside/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter crawlDcinsideStream(@RequestBody CrawlRequest request) {
        SseEmitter emitter = new SseEmitter(-1L); // 타임아웃 없음

        if (request.urls() == null || request.urls().isEmpty()) {
            sendErrorAndComplete(emitter, "URL을 입력해 주세요.");
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            try {
                CrawlResult result = crawlAllUrls(request, event -> sendEvent(emitter, "progress", event));
                sendEvent(emitter, "complete", result.toBody());
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

    private CrawlResult crawlAllUrls(CrawlRequest request, Consumer<CrawlProgressEvent> onProgress) {
        List<String> validUrls = request.urls().stream()
                .map(url -> url == null ? "" : url.trim())
                .filter(url -> !url.isEmpty())
                .toList();

        List<DcinsidePostData> results = new ArrayList<>();
        List<Map<String, String>> errors = new ArrayList<>();
        List<UrlTiming> timings = new ArrayList<>();

        int total = validUrls.size();
        int completed = 0;
        int successCount = 0;
        int failCount = 0;

        for (String trimmed : validUrls) {
            onProgress.accept(new CrawlProgressEvent(
                    completed, total, trimmed, "text-crawl", successCount, failCount
            ));

            StepTimer timer = new StepTimer(log, "crawl-url " + trimmed);
            List<StepTimings> partialTimings = new ArrayList<>();

            try {
                TimedResult<DcinsidePostData> crawled = crawlService.crawl(trimmed);
                partialTimings.add(crawled.timings());
                timer.step("text-crawl");

                onProgress.accept(new CrawlProgressEvent(
                        completed, total, trimmed, "screenshot", successCount, failCount
                ));

                int excelRowNumber = request.startSerial() != null
                        ? request.startSerial() + results.size()
                        : results.size() + 1;
                String postNo = ScreenshotService.extractPostNoFromUrl(trimmed);
                TimedResult<CaptureImage> capture =
                        screenshotService.captureFullPage(trimmed, excelRowNumber, postNo);
                partialTimings.add(capture.timings());
                timer.step("screenshot");

                onProgress.accept(new CrawlProgressEvent(
                        completed, total, trimmed, "attach-capture", successCount, failCount
                ));

                results.add(crawlService.attachCapture(crawled.value(), capture.value()));
                timer.step("attach-capture");

                StepTimings merged = StepTimings.merge(
                        partialTimings.toArray(StepTimings[]::new)
                );
                StepTimings withOuter = mergeWithOuter(timer.finish(), merged);
                timings.add(new UrlTiming(trimmed, true, withOuter.totalMs(), withOuter.normalizedSteps()));

                successCount++;
                completed++;
                onProgress.accept(new CrawlProgressEvent(
                        completed, total, trimmed, "url-done", successCount, failCount
                ));
            } catch (StageTimedException e) {
                partialTimings.add(e.timings());
                log.warn("Crawl failed for {} at {}: {}", trimmed, e.stage(), e.getMessage());
                errors.add(errorEntry(trimmed, e));
                timings.add(buildFailedTiming(trimmed, partialTimings, timer));
                failCount++;
                completed++;
                onProgress.accept(new CrawlProgressEvent(
                        completed, total, trimmed, "url-failed", successCount, failCount
                ));
            } catch (Exception e) {
                log.warn("Crawl failed for {}: {}", trimmed, e.getMessage());
                errors.add(errorEntry(trimmed, e));
                timings.add(buildFailedTiming(trimmed, partialTimings, timer));
                failCount++;
                completed++;
                onProgress.accept(new CrawlProgressEvent(
                        completed, total, trimmed, "url-failed", successCount, failCount
                ));
            }
        }

        return new CrawlResult(results, errors, timings);
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

    private record CrawlResult(
            List<DcinsidePostData> results,
            List<Map<String, String>> errors,
            List<UrlTiming> timings
    ) {
        Map<String, Object> toBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", results);
            body.put("errors", errors);
            body.put("timings", timings);
            return body;
        }
    }
}
