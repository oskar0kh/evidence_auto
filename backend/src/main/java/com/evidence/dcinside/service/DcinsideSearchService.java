package com.evidence.dcinside.service;

import com.evidence.dcinside.dto.GalleryCandidate;
import com.evidence.dcinside.dto.SearchPageEvent;
import com.evidence.dcinside.dto.SearchRequest;
import com.evidence.dcinside.dto.SearchStreamCriteria;
import com.evidence.dcinside.fetch.CrawlHealthTracker;
import com.evidence.dcinside.fetch.DcinsideFetchEscalator;
import com.evidence.dcinside.http.CrawlThrottle;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DcinsideSearchService {

    private static final Logger log = LoggerFactory.getLogger(DcinsideSearchService.class);

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_PAGE_LIMIT = 10;
    private static final int MAX_DATE_RANGE_PAGE_LIMIT = 500;

    private static final DateTimeFormatter SEARCH_RESULT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final DateTimeFormatter REQUEST_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final Pattern GALLERY_ID_FROM_URL_PATTERN =
            Pattern.compile("[?&]id=([^&\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GALLERY_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]+$");

    private final DcinsideFetchEscalator fetchEscalator;
    private final CrawlThrottle crawlThrottle;
    private final int searchPageRetryMaxAttempts;

    public DcinsideSearchService(
            DcinsideFetchEscalator fetchEscalator,
            CrawlThrottle crawlThrottle,
            @Value("${evidence.crawl.search-page-retry-max-attempts:2}") int searchPageRetryMaxAttempts
    ) {
        this.fetchEscalator = fetchEscalator;
        this.crawlThrottle = crawlThrottle;
        this.searchPageRetryMaxAttempts = Math.max(1, searchPageRetryMaxAttempts);
    }

    public record SearchResult(
            List<String> urls,
            boolean dateRangeSearch,
            boolean gallerySearch,
            String galleryId,
            String startDate,
            String endDate
    ) {
    }

    @FunctionalInterface
    public interface SearchUrlConsumer {
        boolean accept(String url) throws Exception;
    }

    @FunctionalInterface
    public interface SearchPageErrorConsumer {
        void accept(String searchUrl, String errorMessage);
    }

    public SearchResult search(SearchRequest request) throws Exception {
        boolean dateRangeSearch = hasDateRange(request.startDate(), request.endDate());
        boolean gallerySearch = hasGalleryId(request.galleryId());
        List<String> urls;
        if (gallerySearch) {
            if (dateRangeSearch) {
                LocalDate startDate = parseRequestDate(request.startDate());
                LocalDate endDate = parseRequestDate(request.endDate());
                urls = searchGalleryByDateRange(
                        request.query(),
                        request.galleryId(),
                        startDate,
                        endDate
                );
            } else {
                urls = searchGallery(request.query(), request.galleryId(), request.maxResults());
            }
        } else if (dateRangeSearch) {
            LocalDate startDate = parseRequestDate(request.startDate());
            LocalDate endDate = parseRequestDate(request.endDate());
            urls = searchIntegratedByDateRange(request.query(), startDate, endDate);
        } else {
            urls = searchIntegrated(request.query(), request.maxResults());
        }

        return new SearchResult(
                urls,
                dateRangeSearch,
                gallerySearch,
                gallerySearch ? normalizeGalleryId(request.galleryId()) : null,
                dateRangeSearch ? request.startDate() : null,
                dateRangeSearch ? request.endDate() : null
        );
    }

    public static boolean hasDateRange(String startDate, String endDate) {
        boolean hasStart = startDate != null && !startDate.isBlank();
        boolean hasEnd = endDate != null && !endDate.isBlank();
        if (hasStart != hasEnd) {
            throw new IllegalArgumentException("검색 기간의 시작일과 종료일을 모두 입력해 주세요.");
        }
        return hasStart;
    }

    public List<String> searchIntegrated(String query, Integer maxResults) throws Exception {
        Set<String> collected = new LinkedHashSet<>();
        SearchStreamCriteria criteria = new SearchStreamCriteria(query, maxResults, null, null, null);
        forEachSearchUrl(criteria, collected, page -> { }, url -> true, null, null, null);
        return new ArrayList<>(collected);
    }

    public List<String> searchIntegratedByDateRange(String query, LocalDate startDate, LocalDate endDate)
            throws Exception {
        validateDateRange(startDate, endDate);
        Set<String> collected = new LinkedHashSet<>();
        SearchStreamCriteria criteria = new SearchStreamCriteria(query, null, startDate, endDate, null);
        forEachSearchUrl(criteria, collected, page -> { }, url -> true, null, null, null);
        return new ArrayList<>(collected);
    }

    public List<String> searchGallery(String query, String galleryId, Integer maxResults) throws Exception {
        String normalizedGalleryId = normalizeGalleryId(galleryId);
        Set<String> collected = new LinkedHashSet<>();
        SearchStreamCriteria criteria = new SearchStreamCriteria(query, maxResults, null, null, normalizedGalleryId);
        forEachSearchUrl(criteria, collected, page -> { }, url -> true, null, null, null);
        return filterUrlsByGalleryId(new ArrayList<>(collected), normalizedGalleryId);
    }

    public List<String> searchGalleryByDateRange(
            String query,
            String galleryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws Exception {
        validateDateRange(startDate, endDate);
        String normalizedGalleryId = normalizeGalleryId(galleryId);
        Set<String> collected = new LinkedHashSet<>();
        SearchStreamCriteria criteria = new SearchStreamCriteria(
                query, null, startDate, endDate, normalizedGalleryId
        );
        forEachSearchUrl(criteria, collected, page -> { }, url -> true, null, null, null);
        return filterUrlsByGalleryId(new ArrayList<>(collected), normalizedGalleryId);
    }

    /**
     * 검색 페이지를 순회하며 URL을 발견할 때마다 {@code onUrl}에 전달합니다.
     * {@code onUrl}이 false를 반환하면 검색을 중단합니다.
     */
    public void forEachSearchUrl(
            SearchStreamCriteria criteria,
            Set<String> seen,
            Consumer<SearchPageEvent> onPage,
            SearchUrlConsumer onUrl,
            SearchPageErrorConsumer onPageError,
            CrawlHealthTracker health
    ) throws Exception {
        forEachSearchUrl(criteria, seen, onPage, onUrl, onPageError, health, null);
    }

    public void forEachSearchUrl(
            SearchStreamCriteria criteria,
            Set<String> seen,
            Consumer<SearchPageEvent> onPage,
            SearchUrlConsumer onUrl,
            SearchPageErrorConsumer onPageError,
            CrawlHealthTracker health,
            IntSupplier processedPostUrlCount
    ) throws Exception {
        if (criteria.isDateRangeSearch()) {
            validateDateRange(criteria.startDate(), criteria.endDate());
        }

        List<String> terms = parseSearchTerms(criteria.query());
        String galleryId = hasGalleryId(criteria.galleryId())
                ? normalizeGalleryId(criteria.galleryId())
                : null;
        boolean dateRange = criteria.isDateRangeSearch();
        int limit = dateRange
                ? Integer.MAX_VALUE
                : resolveMaxResults(criteria.maxResults());
        int maxPages = dateRange ? MAX_DATE_RANGE_PAGE_LIMIT : MAX_PAGE_LIMIT;

        for (int termIdx = 0; termIdx < terms.size(); termIdx++) {
            String term = terms.get(termIdx);
            String encodedQuery = URLEncoder.encode(term, StandardCharsets.UTF_8);
            int termCollected = 0;
            boolean reachedOlderThanRange = false;

            for (int page = 1; page <= maxPages && !reachedOlderThanRange; page++) {
                if (!dateRange && termCollected >= limit) {
                    break;
                }

                onPage.accept(new SearchPageEvent(termIdx + 1, terms.size(), term, page, seen.size()));

                String searchUrl = buildSearchUrl(page, encodedQuery, galleryId);
                Document doc = fetchSearchDocumentWithRetry(searchUrl, health);
                if (doc == null) {
                    String message = "검색 페이지를 불러올 수 없습니다: " + searchUrl;
                    log.warn(message);
                    if (health != null) {
                        health.activateProtectiveMode();
                    }
                    if (onPageError != null) {
                        onPageError.accept(searchUrl, message);
                    }
                    break;
                }

                List<SearchResultItem> pageResults = extractPageResults(doc);
                if (pageResults.isEmpty()) {
                    break;
                }

                for (SearchResultItem item : pageResults) {
                    if (!matchesGalleryId(item.url(), galleryId)) {
                        continue;
                    }

                    if (dateRange) {
                        LocalDate postDate = item.postDate();
                        if (postDate == null) {
                            continue;
                        }
                        if (postDate.isAfter(criteria.endDate())) {
                            continue;
                        }
                        if (postDate.isBefore(criteria.startDate())) {
                            reachedOlderThanRange = true;
                            break;
                        }
                    }

                    if (!seen.add(item.url())) {
                        continue;
                    }

                    termCollected++;
                    if (!onUrl.accept(item.url())) {
                        return;
                    }

                    if (!dateRange && termCollected >= limit) {
                        break;
                    }
                }

                if (!reachedOlderThanRange && page < maxPages) {
                    if (!dateRange && termCollected >= limit) {
                        break;
                    }
                    sleepBeforeSearchRequest(health, processedPostUrlCount);
                }
            }

            if (termIdx < terms.size() - 1) {
                sleepBeforeSearchRequest(health, processedPostUrlCount);
            }
        }
    }

    private void sleepBeforeSearchRequest(CrawlHealthTracker health, IntSupplier processedPostUrlCount)
            throws InterruptedException {
        boolean protective = health != null && health.isProtectiveMode();
        int count = processedPostUrlCount != null ? processedPostUrlCount.getAsInt() : 0;
        crawlThrottle.sleepBeforeRequest(protective, count);
    }

    private Document fetchSearchDocumentWithRetry(String searchUrl, CrawlHealthTracker health) throws InterruptedException {
        Document doc = null;
        for (int attempt = 1; attempt <= searchPageRetryMaxAttempts; attempt++) {
            if (attempt > 1) {
                boolean protective = health != null && health.isProtectiveMode();
                crawlThrottle.sleepBeforeRequest(protective);
            }
            doc = fetchEscalator.fetchSearchDocument(searchUrl, health);
            if (doc != null) {
                return doc;
            }
        }
        return null;
    }

    private static void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("검색 기간의 시작일과 종료일을 모두 입력해 주세요.");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("검색 기간의 시작일은 종료일보다 이후일 수 없습니다.");
        }
    }

    private static int resolveMaxResults(Integer maxResults) {
        if (maxResults == null || maxResults <= 0) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(maxResults, DEFAULT_MAX_RESULTS);
    }

    public List<GalleryCandidate> searchGalleriesByName(String galleryName) throws Exception {
        if (galleryName == null || galleryName.isBlank()) {
            throw new IllegalArgumentException("갤러리명을 입력해 주세요.");
        }

        String trimmedName = galleryName.trim();
        String encodedName = URLEncoder.encode(trimmedName, StandardCharsets.UTF_8);
        String searchUrl = "https://search.dcinside.com/gallery/q/" + encodedName;
        Document doc = fetchEscalator.fetchSearchDocument(searchUrl);
        if (doc == null) {
            throw new IllegalStateException("갤러리 검색 페이지를 불러올 수 없습니다.");
        }

        LinkedHashMap<String, GalleryCandidate> collected = new LinkedHashMap<>();
        appendGalleryCandidates(doc.select("div.gallsch_result_all a.gallname_txt"), collected);
        appendGalleryCandidates(doc.select("div.integrate_recom a.gallname_txt"), collected);

        return new ArrayList<>(collected.values());
    }

    private void appendGalleryCandidates(Elements links, LinkedHashMap<String, GalleryCandidate> collected) {
        for (Element link : links) {
            String href = link.attr("href").trim();
            if (href.isEmpty()) {
                continue;
            }

            Matcher matcher = GALLERY_ID_FROM_URL_PATTERN.matcher(href);
            if (!matcher.find()) {
                continue;
            }

            String galleryId = matcher.group(1).trim();
            if (galleryId.isEmpty() || !GALLERY_ID_PATTERN.matcher(galleryId).matches()) {
                continue;
            }

            String name = extractGalleryName(link);
            if (name.isEmpty()) {
                continue;
            }

            collected.putIfAbsent(
                    galleryId,
                    new GalleryCandidate(name, galleryId, parseGalleryTypeFromUrl(href))
            );
        }
    }

    private String extractGalleryName(Element link) {
        Element clone = link.clone();
        clone.select("em").remove();
        return clone.text().trim();
    }

    private String parseGalleryTypeFromUrl(String href) {
        if (href.contains("/mgallery/")) {
            return "mgallery";
        }
        if (href.contains("/mini/")) {
            return "mini";
        }
        return "main";
    }

    public static String normalizeGalleryId(String galleryId) {
        if (galleryId == null || galleryId.isBlank()) {
            throw new IllegalArgumentException("갤러리 ID를 입력해 주세요.");
        }

        String trimmed = galleryId.trim();
        Matcher matcher = GALLERY_ID_FROM_URL_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            trimmed = matcher.group(1).trim();
        }

        if (trimmed.isEmpty() || !GALLERY_ID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("갤러리 ID 형식이 올바르지 않습니다. 예: programming");
        }
        return trimmed;
    }

    public static boolean hasGalleryId(String galleryId) {
        return galleryId != null && !galleryId.isBlank();
    }

    public static String extractGalleryIdFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        Matcher matcher = GALLERY_ID_FROM_URL_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    public static boolean matchesGalleryId(String url, String galleryId) {
        if (!hasGalleryId(galleryId)) {
            return true;
        }
        String normalizedGalleryId = normalizeGalleryId(galleryId);
        String urlGalleryId = extractGalleryIdFromUrl(url);
        return !urlGalleryId.isEmpty() && normalizedGalleryId.equals(urlGalleryId);
    }

    public static List<String> filterUrlsByGalleryId(List<String> urls, String galleryId) {
        if (!hasGalleryId(galleryId)) {
            return urls;
        }
        String normalizedGalleryId = normalizeGalleryId(galleryId);
        List<String> filtered = new ArrayList<>();
        for (String url : urls) {
            if (matchesGalleryId(url, normalizedGalleryId)) {
                filtered.add(url);
            }
        }
        return filtered;
    }

    public static List<String> parseSearchTerms(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("검색어를 입력해 주세요.");
        }

        String[] parts = query.trim().split("[,\\s]+");
        List<String> terms = new ArrayList<>();
        for (String part : parts) {
            String term = part.trim();
            if (!term.isEmpty()) {
                terms.add(term);
            }
        }

        if (terms.isEmpty()) {
            throw new IllegalArgumentException("검색어를 입력해 주세요.");
        }
        return terms;
    }

    public static LocalDate parseRequestDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), REQUEST_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("날짜 형식은 yyyy-MM-dd 이어야 합니다: " + value);
        }
    }

    private String buildSearchUrl(int page, String encodedQuery, String galleryId) {
        String url = "https://search.dcinside.com/post/p/" + page + "/q/" + encodedQuery;
        if (galleryId != null && !galleryId.isBlank()) {
            url += "/gallery/" + galleryId;
        }
        return url;
    }

    private List<SearchResultItem> extractPageResults(Document doc) {
        Elements items = doc.select("ul.sch_result_list > li");
        List<SearchResultItem> results = new ArrayList<>();
        for (Element item : items) {
            Element link = item.selectFirst("a.tit_txt");
            if (link == null) {
                continue;
            }
            String href = link.attr("href").trim();
            if (href.isEmpty()) {
                continue;
            }
            String normalized = normalizePostUrl(href);
            if (!com.evidence.dcinside.DcinsideConstants.POST_URL_PATTERN.matcher(normalized).find()) {
                continue;
            }

            LocalDate postDate = null;
            Element dateElement = item.selectFirst("span.date_time");
            if (dateElement != null) {
                postDate = parseSearchResultDate(dateElement.text().trim());
            }
            results.add(new SearchResultItem(normalized, postDate));
        }
        return results;
    }

    private LocalDate parseSearchResultDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw, SEARCH_RESULT_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String normalizePostUrl(String url) {
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        if (url.startsWith("/")) {
            return "https://gall.dcinside.com" + url;
        }
        return url.replace("http://", "https://");
    }

    private record SearchResultItem(String url, LocalDate postDate) {
    }
}
