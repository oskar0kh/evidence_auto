package com.evidence.dto;

import java.util.List;

public record CrawlRequest(List<String> urls) {
}
