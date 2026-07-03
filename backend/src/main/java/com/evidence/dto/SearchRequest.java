package com.evidence.dto;

public record SearchRequest(
        String query,
        Integer maxResults
) {
}
