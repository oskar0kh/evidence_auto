package com.evidence.instagram.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InstagramScreenshotServiceTest {

    @Test
    void formatFilename_usesSerialAndShortcode() {
        assertEquals(
                "연번 003_post_DZMwDrmTllS.png",
                InstagramScreenshotService.formatFilename(3, "DZMwDrmTllS")
        );
    }
}
