package com.evidence.dcinside.controller;

import com.evidence.dcinside.dto.GalleryCandidate;
import com.evidence.dcinside.dto.GalleryLookupRequest;
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
            DcinsideSearchService.SearchResult result = searchService.search(request);
            long searchMs = (System.nanoTime() - searchStartNanos) / 1_000_000;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("urls", result.urls());
            body.put("count", result.urls().size());
            body.put("searchMs", searchMs);
            body.put("dateRangeSearch", result.dateRangeSearch());
            body.put("gallerySearch", result.gallerySearch());
            if (result.gallerySearch()) {
                body.put("galleryId", result.galleryId());
            }
            if (result.dateRangeSearch()) {
                body.put("startDate", result.startDate());
                body.put("endDate", result.endDate());
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

    @PostMapping("/dcinside/galleries")
    public ResponseEntity<?> lookupGalleries(@RequestBody GalleryLookupRequest request) {
        long searchStartNanos = System.nanoTime();
        try {
            List<GalleryCandidate> galleries = searchService.searchGalleriesByName(request.name());
            long searchMs = (System.nanoTime() - searchStartNanos) / 1_000_000;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("galleries", galleries);
            body.put("count", galleries.size());
            body.put("searchMs", searchMs);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Gallery lookup failed for name '{}': {}", request.name(), e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unexpected search API error", e);
        Map<String, String> body = new HashMap<>();
        body.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body);
    }
}
