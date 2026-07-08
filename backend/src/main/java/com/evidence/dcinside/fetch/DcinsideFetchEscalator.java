package com.evidence.dcinside.fetch;

import com.evidence.dcinside.http.BlockSignal;
import com.evidence.dcinside.http.DcinsideHttpClient;
import com.evidence.dcinside.service.ScreenshotService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Service
public class DcinsideFetchEscalator {

    private static final Logger log = LoggerFactory.getLogger(DcinsideFetchEscalator.class);

    private final DcinsideHttpClient httpClient;
    private final boolean escalationEnabled;
    private final int consecutiveFailThreshold;
    private final boolean preferBrowserAfterThreshold;

    public DcinsideFetchEscalator(
            DcinsideHttpClient httpClient,
            @Value("${evidence.crawl.escalation.enabled:true}") boolean escalationEnabled,
            @Value("${evidence.crawl.escalation.consecutive-fail-threshold:3}") int consecutiveFailThreshold,
            @Value("${evidence.crawl.escalation.prefer-browser-after-threshold:true}") boolean preferBrowserAfterThreshold
    ) {
        this.httpClient = httpClient;
        this.escalationEnabled = escalationEnabled;
        this.consecutiveFailThreshold = Math.max(1, consecutiveFailThreshold);
        this.preferBrowserAfterThreshold = preferBrowserAfterThreshold;
    }

    public CrawlHealthTracker newHealthTracker() {
        return new CrawlHealthTracker(consecutiveFailThreshold, preferBrowserAfterThreshold);
    }

    public FetchedPage fetchPostPage(
            String url,
            CrawlHealthTracker health,
            ScreenshotService.CaptureSession captureSession
    ) throws Exception {
        if (!health.isProtectiveMode()) {
            try {
                FetchedPage page = fetchHttp(url, url, FetchPhase.HTTP_DESKTOP, false);
                BlockSignal signal = validatePostPage(page.html());
                if (signal == null) {
                    health.recordSuccess(FetchPhase.HTTP_DESKTOP);
                    return page;
                }
                log.warn("Fast fetch validation failed for {}: {}", url, signal);
                health.recordFailure(signal, FetchPhase.HTTP_DESKTOP);
            } catch (Exception e) {
                log.warn("Fast fetch failed for {}: {}", url, e.getMessage());
                health.recordFailure(BlockSignal.HTTP_ERROR, FetchPhase.HTTP_DESKTOP);
            }
            log.info("Switching to protective mode for {}", url);
        }

        return fetchPostPageProtective(url, health, captureSession);
    }

    private FetchedPage fetchPostPageProtective(
            String url,
            CrawlHealthTracker health,
            ScreenshotService.CaptureSession captureSession
    ) throws Exception {
        List<FetchPhase> phases = resolvePhases(health);
        Exception lastError = null;

        for (FetchPhase phase : phases) {
            try {
                FetchedPage page = fetchWithPhase(url, phase, captureSession, health, true);
                BlockSignal signal = validatePostPage(page.html());
                if (signal != null) {
                    health.recordFailure(signal, phase);
                    lastError = new IllegalStateException("페이지를 불러올 수 없습니다. " + signal.displayName());
                    log.warn("Post page validation failed at {} for {}: {}", phase, url, signal);
                    continue;
                }
                health.recordSuccess(phase);
                return page;
            } catch (Exception e) {
                health.recordFailure(BlockSignal.HTTP_ERROR, phase);
                lastError = e;
                log.warn("Fetch phase {} failed for {}: {}", phase, url, e.getMessage());
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("페이지를 불러올 수 없습니다.");
    }

    public Document fetchSearchDocument(String url, CrawlHealthTracker health) {
        boolean resilient = health != null && health.isProtectiveMode();
        try {
            HttpResponse<String> response = httpClient.get(
                    url,
                    "https://search.dcinside.com/",
                    "document",
                    resilient
            );
            return Jsoup.parse(response.body(), url);
        } catch (Exception e) {
            if (health != null) {
                health.activateProtectiveMode();
            }
            log.warn("Search page fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    public Document fetchSearchDocument(String url) {
        return fetchSearchDocument(url, null);
    }

    private FetchedPage fetchWithPhase(
            String url,
            FetchPhase phase,
            ScreenshotService.CaptureSession captureSession,
            CrawlHealthTracker health,
            boolean resilient
    ) throws Exception {
        return switch (phase) {
            case HTTP_DESKTOP -> fetchHttp(url, url, FetchPhase.HTTP_DESKTOP, resilient);
            case HTTP_MOBILE -> fetchHttp(toMobileUrl(url), url, FetchPhase.HTTP_MOBILE, resilient);
            case BROWSER -> captureSession.fetchPageContent(url, health.shouldRelaxBlockTracking());
        };
    }

    private FetchedPage fetchHttp(String fetchUrl, String referer, FetchPhase phase, boolean resilient) throws Exception {
        HttpResponse<String> response = httpClient.get(fetchUrl, referer, "document", resilient);
        return new FetchedPage(fetchUrl, response.body(), phase, referer);
    }

    private List<FetchPhase> resolvePhases(CrawlHealthTracker health) {
        List<FetchPhase> phases = new ArrayList<>();
        if (!escalationEnabled) {
            phases.add(FetchPhase.HTTP_DESKTOP);
            return phases;
        }
        if (health.shouldPreferBrowser()) {
            phases.add(FetchPhase.BROWSER);
        }
        phases.add(FetchPhase.HTTP_DESKTOP);
        phases.add(FetchPhase.HTTP_MOBILE);
        if (!health.shouldPreferBrowser()) {
            phases.add(FetchPhase.BROWSER);
        }
        return phases;
    }

    public static String toMobileUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        return url
                .replace("https://gall.dcinside.com", "https://m.dcinside.com")
                .replace("http://gall.dcinside.com", "https://m.dcinside.com");
    }

    public static BlockSignal validatePostPage(String html) {
        if (html == null || html.isBlank()) {
            return BlockSignal.EMPTY_BODY;
        }
        if (html.contains("정상적인 접근")) {
            return BlockSignal.BOT_CHALLENGE;
        }
        Document doc = Jsoup.parse(html);
        if (doc.selectFirst("div.gallview_head") == null) {
            return BlockSignal.PARSE_FAILURE;
        }
        return null;
    }
}
