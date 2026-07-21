package com.evidence.instagram.dto;

public record InstagramSearchCrawlRequest(
        String query,
        Integer maxResults,
        Integer startSerial
) {
}
