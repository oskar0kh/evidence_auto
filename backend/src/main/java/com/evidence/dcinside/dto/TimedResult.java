package com.evidence.dcinside.dto;

import com.evidence.dcinside.util.StepTimings;

public record TimedResult<T>(T value, StepTimings timings) {
}
