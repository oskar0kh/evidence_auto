package com.evidence.dcinside.config;

import com.evidence.dcinside.service.CrawlTelemetry;
import com.evidence.dcinside.service.CrawlTelemetryBridge;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CrawlTelemetryConfig {

    private final CrawlTelemetry crawlTelemetry;

    public CrawlTelemetryConfig(CrawlTelemetry crawlTelemetry) {
        this.crawlTelemetry = crawlTelemetry;
    }

    @PostConstruct
    void bindTelemetryBridge() {
        CrawlTelemetryBridge.bind(crawlTelemetry);
    }
}
