package com.evidence.dcinside;

import java.util.regex.Pattern;

public final class DcinsideConstants {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static final Pattern POST_URL_PATTERN = Pattern.compile(
            "https?://(?:m\\.)?gall\\.dcinside\\.com/(?:mgallery/board|mini/board|board)/view/\\?.*"
    );

    private DcinsideConstants() {
    }

    public static boolean isPostUrl(String url) {
        return url != null && (POST_URL_PATTERN.matcher(url).find() || url.contains("gall.dcinside.com"));
    }
}
