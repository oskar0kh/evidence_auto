package com.evidence.dcinside;

import java.util.regex.Pattern;

public final class DcinsideConstants {

    public static String userAgent() {
        return DcinsideUserAgent.get();
    }

    public static final Pattern POST_URL_PATTERN = Pattern.compile(
            "https?://(?:m\\.)?gall\\.dcinside\\.com/(?:mgallery/board|mini/board|board)/view/\\?.*"
    );

    private DcinsideConstants() {
    }

    public static boolean isPostUrl(String url) {
        return url != null && (POST_URL_PATTERN.matcher(url).find() || url.contains("gall.dcinside.com"));
    }
}
