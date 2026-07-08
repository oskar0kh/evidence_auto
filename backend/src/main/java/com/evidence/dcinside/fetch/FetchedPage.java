package com.evidence.dcinside.fetch;

import java.util.Map;

public record FetchedPage(
        String url,
        String html,
        FetchPhase phase,
        String referer,
        Map<String, String> cookies
) {
    public FetchedPage(String url, String html, FetchPhase phase, String referer) {
        this(url, html, phase, referer, Map.of());
    }
}
