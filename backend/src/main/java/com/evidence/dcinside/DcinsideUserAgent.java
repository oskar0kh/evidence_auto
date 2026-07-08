package com.evidence.dcinside;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DcinsideUserAgent {

    private static final Pattern CHROME_VERSION_PATTERN =
            Pattern.compile("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)");

    private static volatile String userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private DcinsideUserAgent() {
    }

    public static String get() {
        return userAgent;
    }

    public static void syncFromChromeVersionOutput(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) {
            return;
        }
        Matcher matcher = CHROME_VERSION_PATTERN.matcher(versionOutput);
        if (!matcher.find()) {
            return;
        }
        String chromeVersion = matcher.group(1);
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chromeVersion + " Safari/537.36";
    }
}
