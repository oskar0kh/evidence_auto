package com.evidence.dcinside.service;

import org.openqa.selenium.chrome.ChromeDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Chrome DevTools Protocol로 광고·트래킹 요청을 차단해 캡처 로딩을 가속합니다.
 */
public final class TrackingDomainBlocker {

    private static final Logger log = LoggerFactory.getLogger(TrackingDomainBlocker.class);

    // 광고/트래킹 도메인 차단 패턴
    private static final List<String> BLOCKED_URL_PATTERNS = List.of(
            // Google ads / analytics
            "*://*.doubleclick.net/*",
            "*://*.googlesyndication.com/*",
            "*://*.googleadservices.com/*",
            "*://*.google-analytics.com/*",
            "*://*.analytics.google.com/*",
            "*://*.googletagmanager.com/*",
            "*://*.googletagservices.com/*",
            "*://*.g.doubleclick.net/*",
            "*://*.adservice.google.com/*",
            // Meta
            "*://*.facebook.com/*",
            "*://*.facebook.net/*",
            // Programmatic ads
            "*://*.taboola.com/*",
            "*://*.outbrain.com/*",
            "*://*.criteo.com/*",
            "*://*.criteo.net/*",
            "*://*.adnxs.com/*",
            "*://*.adsrvr.org/*",
            "*://*.rubiconproject.com/*",
            "*://*.pubmatic.com/*",
            "*://*.openx.net/*",
            "*://*.casalemedia.com/*",
            "*://*.2mdn.net/*",
            "*://*.adform.net/*",
            "*://*.smartadserver.com/*",
            "*://*.teads.tv/*",
            "*://*.amazon-adsystem.com/*",
            // Analytics / heatmaps
            "*://*.scorecardresearch.com/*",
            "*://*.quantserve.com/*",
            "*://*.hotjar.com/*",
            "*://*.mixpanel.com/*",
            "*://*.segment.io/*",
            "*://*.segment.com/*",
            "*://*.amplitude.com/*",
            "*://*.chartbeat.com/*",
            "*://*.newrelic.com/*",
            "*://*.nr-data.net/*",
            // 디시인사이드 부가 피드(댓글돌이 등)
            "*://issuefeed.dcinside.com/*",
            "*://*.issuefeed.dcinside.com/*"
    );

    private TrackingDomainBlocker() {
    }

    public static void apply(ChromeDriver driver) {
        try {
            driver.executeCdpCommand("Network.enable", Map.of());
            driver.executeCdpCommand("Network.setBlockedURLs", Map.of("urls", BLOCKED_URL_PATTERNS));
            log.debug("Applied {} blocked URL patterns for screenshot capture", BLOCKED_URL_PATTERNS.size());
        } catch (Exception e) {
            log.warn("Failed to apply tracking domain block list: {}", e.getMessage());
        }
    }
}
