package com.evidence.dcinside.dto;

public record SearchCrawlRequest(
        String query,
        Integer maxResults,
        String startDate,
        String endDate,
        String galleryId,
        Integer startSerial
) {
}
