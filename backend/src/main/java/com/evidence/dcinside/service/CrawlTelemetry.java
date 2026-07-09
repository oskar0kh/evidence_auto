package com.evidence.dcinside.service;

import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class CrawlTelemetry {

    private static final DateTimeFormatter WALL_CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final ThreadLocal<List<String>> events = ThreadLocal.withInitial(ArrayList::new);
    private final ThreadLocal<Long> sessionStartedAtMs = new ThreadLocal<>();

    public void beginSession() {
        events.get().clear();
        sessionStartedAtMs.set(System.currentTimeMillis());
    }

    public void record(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        long startedAt = sessionStartedAtMs.get() != null ? sessionStartedAtMs.get() : System.currentTimeMillis();
        events.get().add(formatTimestampedMessage(message.trim(), startedAt));
    }

    public List<String> drain() {
        List<String> drained = new ArrayList<>(events.get());
        events.remove();
        sessionStartedAtMs.remove();
        return Collections.unmodifiableList(drained);
    }

    public void clear() {
        events.remove();
        sessionStartedAtMs.remove();
    }

    static String formatTimestampedMessage(String message, long sessionStartedAtMs) {
        if (message.matches("^\\[\\d{2}:\\d{2}/\\d{2}:\\d{2}:\\d{2}\\].*")) {
            return message;
        }
        long now = System.currentTimeMillis();
        String wallClock = LocalTime.now().format(WALL_CLOCK_FORMAT);
        String elapsed = formatElapsed(Math.max(0L, now - sessionStartedAtMs));
        return "[" + wallClock + "/" + elapsed + "] " + message;
    }

    private static String formatElapsed(long elapsedMs) {
        long totalSeconds = elapsedMs / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
