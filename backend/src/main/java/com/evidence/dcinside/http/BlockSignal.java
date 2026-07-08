package com.evidence.dcinside.http;

public enum BlockSignal {
    HTTP_ERROR,
    BOT_CHALLENGE,
    EMPTY_BODY,
    PARSE_FAILURE;

    public static BlockSignal detect(int statusCode, String body) {
        if (statusCode != 200) {
            return HTTP_ERROR;
        }
        if (body == null || body.isBlank()) {
            return EMPTY_BODY;
        }
        if (body.contains("정상적인 접근")) {
            return BOT_CHALLENGE;
        }
        return null;
    }

    public boolean isRetryable() {
        return this == HTTP_ERROR || this == BOT_CHALLENGE;
    }

    public String displayName() {
        return switch (this) {
            case HTTP_ERROR -> "HTTP 오류";
            case BOT_CHALLENGE -> "봇 차단";
            case EMPTY_BODY -> "빈 응답";
            case PARSE_FAILURE -> "파싱 실패";
        };
    }
}
