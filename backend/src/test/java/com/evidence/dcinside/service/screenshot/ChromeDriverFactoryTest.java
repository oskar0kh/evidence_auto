package com.evidence.dcinside.service.screenshot;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromeDriverFactoryTest {

    @Test
    void detectsDriverStartTimeout() {
        assertTrue(ChromeDriverFactory.isDriverStartFailure(
                new IllegalStateException("ChromeDriver 시작 시간 초과 (30초)", new TimeoutException())
        ));
    }

    @Test
    void detectsSessionCreationFailure() {
        assertTrue(ChromeDriverFactory.isDriverStartFailure(
                new RuntimeException("Could not start a new session")
        ));
    }

    @Test
    void ignoresUnrelatedFailures() {
        assertFalse(ChromeDriverFactory.isDriverStartFailure(
                new IllegalStateException("댓글 API 호출에 실패했습니다.")
        ));
    }
}
