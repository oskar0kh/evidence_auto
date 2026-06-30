package com.evidence.controller;

import com.evidence.dto.CrawlRequest;
import com.evidence.dto.DcinsidePostData;
import com.evidence.service.DcinsideCrawlService;
import com.evidence.service.ScreenshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
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

        for (String url : request.urls()) {
            String trimmed = url == null ? "" : url.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                DcinsidePostData crawled = crawlService.crawl(trimmed);
                int excelRowNumber = request.startSerial() != null
                        ? request.startSerial() + results.size()
                        : results.size() + 1;
                String postNo = ScreenshotService.extractPostNoFromUrl(trimmed);
                Path captureFile = screenshotService.captureFullPage(trimmed, excelRowNumber, postNo);
                results.add(crawlService.attachCapture(crawled, captureFile));
            } catch (Exception e) {
                log.warn("Crawl failed for {}: {}", trimmed, e.getMessage());
                errors.add(errorEntry(trimmed, e));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", results);
        body.put("errors", errors);
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unexpected crawl API error", e);
        Map<String, String> body = new HashMap<>();
        body.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body);
    }

    private static Map<String, String> errorEntry(String url, Exception e) {
        Map<String, String> entry = new HashMap<>();
        entry.put("url", url);
        entry.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return entry;
    }
}
