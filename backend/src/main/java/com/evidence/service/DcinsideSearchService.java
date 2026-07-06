package com.evidence.service;

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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DcinsideSearchService {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_PAGE_LIMIT = 10;

    private static final Pattern POST_URL_PATTERN = Pattern.compile(
            "https?://(?:m\\.)?gall\\.dcinside\\.com/(?:mgallery/board|mini/board|board)/view/\\?.*"
    );

    public List<String> searchIntegrated(String query, Integer maxResults) throws Exception {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("검색어를 입력해 주세요.");
        }

        int limit = maxResults == null || maxResults <= 0 ? DEFAULT_MAX_RESULTS : Math.min(maxResults, DEFAULT_MAX_RESULTS);
        String encodedQuery = URLEncoder.encode(trimmed, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Set<String> collected = new LinkedHashSet<>();
        for (int page = 1; page <= MAX_PAGE_LIMIT && collected.size() < limit; page++) {
            String searchUrl = "https://search.dcinside.com/post/p/" + page + "/q/" + encodedQuery;
            Document doc = fetchDocument(client, searchUrl);
            Elements resultLinks = doc.select("ul.sch_result_list a.tit_txt");
            if (resultLinks.isEmpty()) {
                break;
            }

            List<String> pageUrls = extractPostUrls(resultLinks);

            for (String url : pageUrls) {
                collected.add(url);
                if (collected.size() >= limit) {
                    break;
                }
            }

            if (page < MAX_PAGE_LIMIT && collected.size() < limit) {
                Thread.sleep(500);
            }
        }

        return new ArrayList<>(collected);
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

    private List<String> extractPostUrls(Elements links) {
        List<String> urls = new ArrayList<>();
        for (Element link : links) {
            String href = link.attr("href").trim();
            if (href.isEmpty()) {
                continue;
            }
            String normalized = normalizePostUrl(href);
            if (POST_URL_PATTERN.matcher(normalized).find()) {
                urls.add(normalized);
            }
        }
        return urls;
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
}
