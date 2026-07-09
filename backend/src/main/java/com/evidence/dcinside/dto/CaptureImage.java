package com.evidence.dcinside.dto;

public record CaptureImage(
        String filename,
        byte[] pngBytes
) {
}
