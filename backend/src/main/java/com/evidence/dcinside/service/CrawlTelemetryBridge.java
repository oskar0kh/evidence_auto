package com.evidence.dcinside.service;

/**
 * 정적 팩토리(ChromeDriverFactory 등)에서 Spring CrawlTelemetry로 이벤트를 전달합니다.
 */
public final class CrawlTelemetryBridge {

    private static volatile CrawlTelemetry telemetry;

    private CrawlTelemetryBridge() {
    }

    public static void bind(CrawlTelemetry crawlTelemetry) {
        telemetry = crawlTelemetry;
    }

    public static void record(String message) {
        if (telemetry != null) {
            telemetry.record(message);
        }
    }
}
