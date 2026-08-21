package com.nevis.search.api.evaluation;

import com.nevis.search.application.evaluation.SemanticRetrievalMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record EvaluationSemanticPlanRequest(
        @NotBlank String query,
        @NotNull SemanticRetrievalMode semanticRetrieval,
        @Positive Integer chunkCandidateLimit,
        @Positive Integer hnswEfSearch
) {
}
