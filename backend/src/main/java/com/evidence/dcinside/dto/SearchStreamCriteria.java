package com.evidence.dcinside.dto;

import java.time.LocalDate;

public record SearchStreamCriteria(
        String query,
        Integer maxResults,
        LocalDate startDate,
        LocalDate endDate,
        String galleryId
) {
    public boolean isDateRangeSearch() {
        return startDate != null && endDate != null;
    }
}
