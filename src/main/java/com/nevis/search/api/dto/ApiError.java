package com.nevis.search.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldViolation> violations
) {
}

