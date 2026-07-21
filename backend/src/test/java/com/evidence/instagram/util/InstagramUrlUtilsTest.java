package com.evidence.instagram.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstagramUrlUtilsTest {

    @Test
    void shortcodeToMediaId_knownValue() {
        // DZV6ATHEmPc → 3915290558285898716 (사용자 HTML 샘플)
        assertEquals("3915290558285898716", InstagramUrlUtils.shortcodeToMediaId("DZV6ATHEmPc"));
    }

    @Test
    void extractShortcode_fromUrl() {
        assertEquals(
                "DZV6ATHEmPc",
                InstagramUrlUtils.extractShortcode("https://www.instagram.com/re_family_rescue/p/DZV6ATHEmPc/")
        );
    }
}
