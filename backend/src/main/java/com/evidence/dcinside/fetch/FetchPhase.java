package com.evidence.dcinside.fetch;

public enum FetchPhase {
    HTTP_DESKTOP("http-desktop"),
    HTTP_MOBILE("http-mobile"),
    BROWSER("browser");

    private final String id;

    FetchPhase(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return switch (this) {
            case HTTP_DESKTOP -> "HTTP(데스크톱)";
            case HTTP_MOBILE -> "HTTP(모바일)";
            case BROWSER -> "브라우저";
        };
    }
}
