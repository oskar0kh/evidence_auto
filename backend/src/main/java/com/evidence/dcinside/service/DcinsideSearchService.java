package com.evidence.dcinside.service;

import com.evidence.dcinside.dto.GalleryCandidate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DcinsideSearchService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_PAGE_LIMIT = 10;
    private static final int MAX_DATE_RANGE_PAGE_LIMIT = 500;
    private static final int TERM_SEARCH_DELAY_MS = 500;

    private static final DateTimeFormatter SEARCH_RESULT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");
    private static final DateTimeFormatter REQUEST_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final Pattern POST_URL_PATTERN = Pattern.compile(
            "https?://(?:m\\.)?gall\\.dcinside\\.com/(?:mgallery/board|mini/board|board)/view/\\?.*"
    );
    private static final Pattern GALLERY_ID_FROM_URL_PATTERN =
            Pattern.compile("[?&]id=([^&\\s]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GALLERY_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]+$");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public List<String> searchIntegrated(String query, Integer maxResults) throws Exception {
        List<String> terms = parseSearchTerms(query);
        Set<String> collected = new LinkedHashSet<>();

        for (int i = 0; i < terms.size(); i++) {
            collected.addAll(searchIntegratedSingle(terms.get(i), maxResults));
            if (i < terms.size() - 1) {
                Thread.sleep(TERM_SEARCH_DELAY_MS);
            }
        }

        return new ArrayList<>(collected);
    }

    public List<String> searchIntegratedByDateRange(String query, LocalDate startDate, LocalDate endDate)
            throws Exception {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("검색 기간의 시작일과 종료일을 모두 입력해 주세요.");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("검색 기간의 시작일은 종료일보다 이후일 수 없습니다.");
        }

        List<String> terms = parseSearchTerms(query);
        Set<String> collected = new LinkedHashSet<>();

        for (int i = 0; i < terms.size(); i++) {
            collected.addAll(searchIntegratedByDateRangeSingle(terms.get(i), startDate, endDate));
            if (i < terms.size() - 1) {
                Thread.sleep(TERM_SEARCH_DELAY_MS);
            }
        }

        return new ArrayList<>(collected);
    }

    public List<String> searchGallery(String query, String galleryId, Integer maxResults) throws Exception {
        String normalizedGalleryId = normalizeGalleryId(galleryId);
        List<String> terms = parseSearchTerms(query);
        Set<String> collected = new LinkedHashSet<>();

        for (int i = 0; i < terms.size(); i++) {
            collected.addAll(searchSingle(terms.get(i), maxResults, normalizedGalleryId));
            if (i < terms.size() - 1) {
                Thread.sleep(TERM_SEARCH_DELAY_MS);
            }
        }

        return filterUrlsByGalleryId(new ArrayList<>(collected), normalizedGalleryId);
    }

    public List<String> searchGalleryByDateRange(
            String query,
            String galleryId,
            LocalDate startDate,
            LocalDate endDate
    ) throws Exception {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("검색 기간의 시작일과 종료일을 모두 입력해 주세요.");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("검색 기간의 시작일은 종료일보다 이후일 수 없습니다.");
        }

        String normalizedGalleryId = normalizeGalleryId(galleryId);
        List<String> terms = parseSearchTerms(query);
        Set<String> collected = new LinkedHashSet<>();

        for (int i = 0; i < terms.size(); i++) {
            collected.addAll(searchByDateRangeSingle(terms.get(i), startDate, endDate, normalizedGalleryId));
            if (i < terms.size() - 1) {
                Thread.sleep(TERM_SEARCH_DELAY_MS);
            }
        }

        return filterUrlsByGalleryId(new ArrayList<>(collected), normalizedGalleryId);
    }

    public List<GalleryCandidate> searchGalleriesByName(String galleryName) throws Exception {
        if (galleryName == null || galleryName.isBlank()) {
            throw new IllegalArgumentException("갤러리명을 입력해 주세요.");
        }

        String trimmedName = galleryName.trim();
        String encodedName = URLEncoder.encode(trimmedName, StandardCharsets.UTF_8);
        String searchUrl = "https://search.dcinside.com/gallery/q/" + encodedName;
        Document doc = fetchDocument(httpClient, searchUrl);

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

    private List<String> searchIntegratedSingle(String term, Integer maxResults) throws Exception {
        return searchSingle(term, maxResults, null);
    }

    private List<String> searchIntegratedByDateRangeSingle(String term, LocalDate startDate, LocalDate endDate)
            throws Exception {
        return searchByDateRangeSingle(term, startDate, endDate, null);
    }

    private List<String> searchSingle(String term, Integer maxResults, String galleryId) throws Exception {
        int limit = maxResults == null || maxResults <= 0 ? DEFAULT_MAX_RESULTS : Math.min(maxResults, DEFAULT_MAX_RESULTS);
        String encodedQuery = URLEncoder.encode(term, StandardCharsets.UTF_8);

        Set<String> collected = new LinkedHashSet<>();
        for (int page = 1; page <= MAX_PAGE_LIMIT && collected.size() < limit; page++) {
            String searchUrl = buildSearchUrl(page, encodedQuery, galleryId);
            Document doc = fetchDocument(httpClient, searchUrl);
            List<SearchResultItem> pageResults = extractPageResults(doc);
            if (pageResults.isEmpty()) {
                break;
            }

            for (SearchResultItem item : pageResults) {
                if (!matchesGalleryId(item.url(), galleryId)) {
                    continue;
                }
                collected.add(item.url());
                if (collected.size() >= limit) {
                    break;
                }
            }

            if (page < MAX_PAGE_LIMIT && collected.size() < limit) {
                Thread.sleep(TERM_SEARCH_DELAY_MS);
            }
        }

        return new ArrayList<>(collected);
    }

    private List<String> searchByDateRangeSingle(
            String term,
            LocalDate startDate,
            LocalDate endDate,
            String galleryId
    ) throws Exception {
        String encodedQuery = URLEncoder.encode(term, StandardCharsets.UTF_8);

        Set<String> collected = new LinkedHashSet<>();
        boolean reachedOlderThanRange = false;

        for (int page = 1; page <= MAX_DATE_RANGE_PAGE_LIMIT && !reachedOlderThanRange; page++) {
            String searchUrl = buildSearchUrl(page, encodedQuery, galleryId);
            Document doc = fetchDocument(httpClient, searchUrl);
            List<SearchResultItem> pageResults = extractPageResults(doc);
            if (pageResults.isEmpty()) {
                break;
            }

            for (SearchResultItem item : pageResults) {
                if (!matchesGalleryId(item.url(), galleryId)) {
                    continue;
                }
                LocalDate postDate = item.postDate();
                if (postDate == null) {
                    continue;
                }
                if (postDate.isAfter(endDate)) {
                    continue;
                }
                if (postDate.isBefore(startDate)) {
                    reachedOlderThanRange = true;
                    break;
                }
                collected.add(item.url());
            }

            if (!reachedOlderThanRange && page < MAX_DATE_RANGE_PAGE_LIMIT) {
                Thread.sleep(TERM_SEARCH_DELAY_MS);
            }
        }

        return new ArrayList<>(collected);
    }

    private String buildSearchUrl(int page, String encodedQuery, String galleryId) {
        String url = "https://search.dcinside.com/post/p/" + page + "/q/" + encodedQuery;
        if (galleryId != null && !galleryId.isBlank()) {
            url += "/gallery/" + galleryId;
        }
        return url;
    }

    private Document fetchDocument(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("검색 페이지를 불러올 수 없습니다. HTTP " + response.statusCode());
        }
        return Jsoup.parse(response.body(), url);
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
            if (!POST_URL_PATTERN.matcher(normalized).find()) {
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
