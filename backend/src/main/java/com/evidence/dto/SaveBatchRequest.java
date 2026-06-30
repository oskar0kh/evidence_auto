package com.evidence.dto;

import java.util.List;

public record SaveBatchRequest(
        String directoryPath,
        List<SaveCaptureItem> captures,
        SaveExcelItem excel
) {
}
