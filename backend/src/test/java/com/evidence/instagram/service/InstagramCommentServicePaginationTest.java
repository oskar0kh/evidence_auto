package com.evidence.instagram.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InstagramCommentServicePaginationTest {

    @Test
    void buildCommentsApiUrl_includesMaxAndMinId() throws Exception {
        Method method = InstagramCommentService.class.getDeclaredMethod(
                "buildCommentsApiUrl",
                String.class,
                String.class,
                String.class
        );
        method.setAccessible(true);

        String withMax = (String) method.invoke(null, "123", "max-cursor", null);
        assertTrue(withMax.contains("/media/123/comments/"));
        assertTrue(withMax.contains("max_id=max-cursor"));

        String withMin = (String) method.invoke(null, "123", null, "min-cursor");
        assertTrue(withMin.contains("min_id=min-cursor"));
    }
}
