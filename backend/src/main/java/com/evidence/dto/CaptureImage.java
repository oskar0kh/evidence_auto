package com.evidence.dto;

public record CaptureImage(
        String filename,
        byte[] pngBytes
) {
}
