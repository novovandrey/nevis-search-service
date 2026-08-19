package com.nevis.search.api.evaluation;

import com.nevis.search.application.evaluation.SearchEvaluationMode;
import com.nevis.search.application.evaluation.SearchEvaluationOverrides;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record EvaluationSearchRequest(
        @NotBlank String query,
        @NotNull SearchEvaluationMode mode,
        @DecimalMin("-1.0") @DecimalMax("1.0") Double minimumSimilarity,
        @Positive Integer candidateLimit,
        @Positive Integer rrfK,
        @Positive Double lexicalWeight,
        @Positive Double vectorWeight
) {

    public SearchEvaluationOverrides overrides() {
        return new SearchEvaluationOverrides(candidateLimit, rrfK, minimumSimilarity, lexicalWeight, vectorWeight);
    }
}
