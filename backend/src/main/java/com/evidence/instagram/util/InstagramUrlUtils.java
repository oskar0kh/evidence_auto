package com.evidence.instagram.util;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstagramUrlUtils {

    private static final Pattern POST_URL_PATTERN = Pattern.compile(
            "instagram\\.com/(?:p|reel|tv)/([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE
    );
    private static final String SHORTCODE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private InstagramUrlUtils() {
    }

    public static boolean isInstagramPostUrl(String url) {
        return url != null && POST_URL_PATTERN.matcher(url).find();
    }

    public static String extractShortcode(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Instagram URL이 비어 있습니다.");
        }
        Matcher matcher = POST_URL_PATTERN.matcher(url);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Instagram 게시물 URL이 아닙니다: " + url);
        }
        return matcher.group(1);
    }

    public static String normalizeUrl(String url) {
        String shortcode = extractShortcode(url);
        return "https://www.instagram.com/p/" + shortcode + "/";
    }

    /** Instagram shortcode → numeric media pk (GraphQL media_id). */
    public static String shortcodeToMediaId(String shortcode) {
        if (shortcode == null || shortcode.isBlank()) {
            throw new IllegalArgumentException("shortcode가 비어 있습니다.");
        }
        BigInteger n = BigInteger.ZERO;
        BigInteger sixtyFour = BigInteger.valueOf(64);
        for (int i = 0; i < shortcode.length(); i++) {
            int idx = SHORTCODE_ALPHABET.indexOf(shortcode.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("잘못된 shortcode: " + shortcode);
            }
            n = n.multiply(sixtyFour).add(BigInteger.valueOf(idx));
        }
        return n.toString();
    }
}
