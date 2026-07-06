package com.evidence.util;

import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 구간별 소요 시간(ms)을 로그로 남기는 헬퍼.
 * 각 step() 호출 시 직전 step 이후 경과 시간을 기록한다.
 */
public final class StepTimer {

    private final Logger log;
    private final String scope;
    private final long totalStartNanos;
    private long stepStartNanos;
    private final Map<String, Long> steps = new LinkedHashMap<>();

    public StepTimer(Logger log, String scope) {
        this.log = log;
        this.scope = scope;
        this.totalStartNanos = System.nanoTime();
        this.stepStartNanos = totalStartNanos;
    }

    public void step(String name) {
        long now = System.nanoTime();
        long stepMs = (now - stepStartNanos) / 1_000_000;
        steps.put(name, stepMs);
        log.info("[timing] {} | {}: {}ms", scope, name, stepMs);
        stepStartNanos = now;
    }

    public StepTimings finish() {
        long totalMs = (System.nanoTime() - totalStartNanos) / 1_000_000;
        log.info("[timing] {} | total: {}ms", scope, totalMs);
        return new StepTimings(scope, Map.copyOf(steps), totalMs);
    }

    public void done() {
        finish();
    }
}
