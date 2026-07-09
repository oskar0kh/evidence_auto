package com.evidence.dcinside.http;

public final class ConnectionFailureDetector {

    private ConnectionFailureDetector() {
    }

    public static boolean isConnectionFailure(Throwable error) {
        if (error == null) {
            return false;
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getCause() != null && isConnectionFailure(error.getCause());
        }
        String lower = message.toLowerCase();
        return lower.contains("connect timed out")
                || lower.contains("connection timed out")
                || lower.contains("request timed out")
                || lower.contains("terminated the handshake")
                || lower.contains("header parser received no bytes")
                || lower.contains("connection reset")
                || lower.contains("broken pipe")
                || lower.contains("no route to host")
                || lower.contains("network is unreachable");
    }
}
