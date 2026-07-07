package com.evidence.dcinside.dto;

import java.util.List;

public record CrawlRequest(
        List<String> urls,
        Integer startSerial
) {
}
