package com.evidence.dcinside.exception;

import com.evidence.dcinside.util.StepTimings;

public class StageTimedException extends Exception {

    private final String stage;
    private final StepTimings timings;

    public StageTimedException(String stage, Throwable cause, StepTimings timings) {
        super(cause);
        this.stage = stage;
        this.timings = timings;
    }

    public String stage() {
        return stage;
    }

    public StepTimings timings() {
        return timings;
    }

    @Override
    public String getMessage() {
        Throwable cause = getCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return cause != null ? cause.getClass().getSimpleName() : "알 수 없는 오류";
    }
}
