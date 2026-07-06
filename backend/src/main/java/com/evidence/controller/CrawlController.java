package com.evidence.controller;

import com.evidence.dto.CaptureImage;
import com.evidence.dto.CrawlRequest;
import com.evidence.dto.DcinsidePostData;
import com.evidence.dto.TimedResult;
import com.evidence.dto.UrlTiming;
import com.evidence.service.DcinsideCrawlService;
import com.evidence.service.ScreenshotService;
import com.evidence.service.StageTimedException;
import com.evidence.util.StepTimer;
import com.evidence.util.StepTimings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/crawl")
public class CrawlController {

    private static final Logger log = LoggerFactory.getLogger(CrawlController.class);

    private final DcinsideCrawlService crawlService;
    private final ScreenshotService screenshotService;

    public CrawlController(DcinsideCrawlService crawlService, ScreenshotService screenshotService) {
        this.crawlService = crawlService;
        this.screenshotService = screenshotService;
    }

    @PostMapping("/dcinside")
    public ResponseEntity<?> crawlDcinside(@RequestBody CrawlRequest request) {
        if (request.urls() == null || request.urls().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "URL을 입력해 주세요."));
        }

        List<DcinsidePostData> results = new ArrayList<>();
        List<Map<String, String>> errors = new ArrayList<>();
        List<UrlTiming> timings = new ArrayList<>();

        for (String url : request.urls()) {
            String trimmed = url == null ? "" : url.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            StepTimer timer = new StepTimer(log, "crawl-url " + trimmed);
            List<StepTimings> partialTimings = new ArrayList<>();

            try {
                TimedResult<DcinsidePostData> crawled = crawlService.crawl(trimmed);
                partialTimings.add(crawled.timings());
                timer.step("text-crawl");

                int excelRowNumber = request.startSerial() != null
                        ? request.startSerial() + results.size()
                        : results.size() + 1;
                String postNo = ScreenshotService.extractPostNoFromUrl(trimmed);
                TimedResult<CaptureImage> capture =
                        screenshotService.captureFullPage(trimmed, excelRowNumber, postNo);
                partialTimings.add(capture.timings());
                timer.step("screenshot");

                results.add(crawlService.attachCapture(crawled.value(), capture.value()));
                timer.step("attach-capture");

                StepTimings merged = StepTimings.merge(
                        partialTimings.toArray(StepTimings[]::new)
                );
                StepTimings withOuter = mergeWithOuter(timer.finish(), merged);
                timings.add(new UrlTiming(trimmed, true, withOuter.totalMs(), withOuter.normalizedSteps()));
            } catch (StageTimedException e) {
                partialTimings.add(e.timings());
                log.warn("Crawl failed for {} at {}: {}", trimmed, e.stage(), e.getMessage());
                errors.add(errorEntry(trimmed, e));
                timings.add(buildFailedTiming(trimmed, partialTimings, timer));
            } catch (Exception e) {
                log.warn("Crawl failed for {}: {}", trimmed, e.getMessage());
                errors.add(errorEntry(trimmed, e));
                timings.add(buildFailedTiming(trimmed, partialTimings, timer));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", results);
        body.put("errors", errors);
        body.put("timings", timings);
        return ResponseEntity.ok(body);
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
}
