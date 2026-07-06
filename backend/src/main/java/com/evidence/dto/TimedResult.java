package com.evidence.dto;

import com.evidence.util.StepTimings;

public record TimedResult<T>(T value, StepTimings timings) {
}
