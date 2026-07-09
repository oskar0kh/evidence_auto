package com.evidence.dcinside.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFailureDetectorTest {

    @Test
    void detectsConnectTimeout() {
        assertTrue(ConnectionFailureDetector.isConnectionFailure(
                new RuntimeException("HTTP connect timed out")
        ));
    }

    @Test
    void detectsTlsHandshakeTermination() {
        assertTrue(ConnectionFailureDetector.isConnectionFailure(
                new RuntimeException("Remote host terminated the handshake")
        ));
    }

    @Test
    void ignoresBotChallengeResponses() {
        assertFalse(ConnectionFailureDetector.isConnectionFailure(
                new IllegalStateException("페이지를 불러올 수 없습니다. 봇 차단이 감지되었습니다.")
        ));
    }
}
