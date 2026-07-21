package com.evidence.instagram.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class InstagramGraphqlUtil {

    private InstagramGraphqlUtil() {
    }

    public static String stripJsonGuard(String body) {
        if (body == null) {
            return "";
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("for (;;);")) {
            return trimmed.substring("for (;;);".length()).trim();
        }
        return trimmed;
    }

    public static boolean isHtmlBody(String body) {
        if (body == null || body.isBlank()) {
            return true;
        }
        String trimmed = stripJsonGuard(body);
        return trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html");
    }

    public static JsonNode parseRoot(ObjectMapper objectMapper, String body) throws Exception {
        String json = stripJsonGuard(body);
        if (isHtmlBody(json)) {
            return null;
        }
        return objectMapper.readTree(json);
    }
}
