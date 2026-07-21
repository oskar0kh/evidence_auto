package com.evidence.instagram.dto;

import java.util.List;

public record InstagramCrawlRequest(
        List<String> urls,
        Integer startSerial,
        String searchQuery
) {
}
