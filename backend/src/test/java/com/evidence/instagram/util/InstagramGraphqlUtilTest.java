package com.evidence.instagram.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InstagramGraphqlUtilTest {

    @Test
    void stripJsonGuard_removesPrefix() {
        assertEquals("{\"ok\":true}", InstagramGraphqlUtil.stripJsonGuard("for (;;);{\"ok\":true}"));
    }

    @Test
    void isHtmlBody_falseForJson() {
        assertFalse(InstagramGraphqlUtil.isHtmlBody("for (;;);{\"data\":{}}"));
    }
}
