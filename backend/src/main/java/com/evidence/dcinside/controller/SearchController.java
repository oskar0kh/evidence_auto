package com.evidence.dcinside.controller;

import com.evidence.dcinside.dto.SearchRequest;
import com.evidence.dcinside.service.DcinsideSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final DcinsideSearchService searchService;

    public SearchController(DcinsideSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/dcinside")
    public ResponseEntity<?> searchDcinside(@RequestBody SearchRequest request) {
        long searchStartNanos = System.nanoTime();
        try {
            List<String> urls;
            boolean dateRangeSearch = hasDateRange(request);
            if (dateRangeSearch) {
                LocalDate startDate = DcinsideSearchService.parseRequestDate(request.startDate());
                LocalDate endDate = DcinsideSearchService.parseRequestDate(request.endDate());
                urls = searchService.searchIntegratedByDateRange(request.query(), startDate, endDate);
            } else {
                urls = searchService.searchIntegrated(request.query(), request.maxResults());
            }

            long searchMs = (System.nanoTime() - searchStartNanos) / 1_000_000;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("urls", urls);
            body.put("count", urls.size());
            body.put("searchMs", searchMs);
            body.put("dateRangeSearch", dateRangeSearch);
            if (dateRangeSearch) {
                body.put("startDate", request.startDate());
                body.put("endDate", request.endDate());
            }
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Search failed for query '{}': {}", request.query(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
        }
    }

    private static boolean hasDateRange(SearchRequest request) {
        boolean hasStart = request.startDate() != null && !request.startDate().isBlank();
        boolean hasEnd = request.endDate() != null && !request.endDate().isBlank();
        if (hasStart != hasEnd) {
            throw new IllegalArgumentException("검색 기간의 시작일과 종료일을 모두 입력해 주세요.");
        }
        return hasStart;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unexpected search API error", e);
        Map<String, String> body = new HashMap<>();
        body.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body);
    }
}
