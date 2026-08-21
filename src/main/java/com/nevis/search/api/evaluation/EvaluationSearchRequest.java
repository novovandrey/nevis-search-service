package com.nevis.search.api.evaluation;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.nevis.search.application.evaluation.SearchEvaluationMode;
import com.nevis.search.application.evaluation.SearchEvaluationOverrides;
import com.nevis.search.application.evaluation.SemanticRetrievalMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record EvaluationSearchRequest(
        @NotBlank String query,
        @NotNull SearchEvaluationMode mode,
        SemanticRetrievalMode semanticRetrieval,
        @DecimalMin("-1.0") @DecimalMax("1.0") Double minimumSimilarity,
        @JsonAlias("candidateLimit") @Positive Integer documentCandidateLimit,
        @Positive Integer chunkCandidateLimit,
        @Positive Integer hnswEfSearch,
        @Positive Integer rrfK,
        @Positive Double lexicalWeight,
        @Positive Double vectorWeight
) {

    public SearchEvaluationOverrides overrides() {
        return new SearchEvaluationOverrides(
                documentCandidateLimit, chunkCandidateLimit, hnswEfSearch,
                rrfK, minimumSimilarity, lexicalWeight, vectorWeight
        );
    }

    public SemanticRetrievalMode resolvedSemanticRetrieval() {
        return semanticRetrieval == null ? SemanticRetrievalMode.HNSW : semanticRetrieval;
    }
}
