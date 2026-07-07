package com.evidence.dcinside.dto;

public record SearchPageEvent(
        int termIndex,
        int termTotal,
        String term,
        int page,
        int discoveredCount
) {
}
