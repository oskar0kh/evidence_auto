package com.evidence.instagram.http;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstagramHttpClientCookieTest {

    @Test
    void buildCookieHeaderFromSelenium_keepsEssentialCookies() {
        String header = InstagramHttpClient.buildCookieHeaderFromSelenium(java.util.List.of(
                new org.openqa.selenium.Cookie("sessionid", "SID123"),
                new org.openqa.selenium.Cookie("csrftoken", "CSRF"),
                new org.openqa.selenium.Cookie("ds_user_id", "42"),
                new org.openqa.selenium.Cookie("unwanted", "nope")
        ));
        assertTrue(header.contains("sessionid=SID123"));
        assertTrue(header.contains("csrftoken=CSRF"));
        assertTrue(header.contains("ds_user_id=42"));
        assertEquals(false, header.contains("unwanted"));
        assertEquals("sessionid=SID123; csrftoken=CSRF; ds_user_id=42", header);
    }

    @Test
    void parseCookieHeader_roundTrip() {
        Map<String, String> parsed = InstagramHttpClient.parseCookieHeader(
                "sessionid=abc; csrftoken=xyz; ds_user_id=1"
        );
        assertEquals("abc", parsed.get("sessionid"));
        assertEquals("xyz", parsed.get("csrftoken"));
        assertEquals("1", parsed.get("ds_user_id"));
    }
}
