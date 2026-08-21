package com.nevis.search.api.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nevis.search.application.evaluation.SearchEvaluationMode;
import com.nevis.search.application.evaluation.SearchEvaluationOverrides.SearchEvaluationParameters;
import com.nevis.search.application.evaluation.SearchEvaluationResult;
import com.nevis.search.application.evaluation.SemanticRetrievalMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record EvaluationSearchResponse(
        String query,
        SearchEvaluationMode mode,
        SemanticRetrievalMode semanticRetrieval,
        SearchEvaluationParametersResponse parameters,
        List<LexicalResult> lexical,
        List<ChunkResult> chunks,
        List<SemanticResult> semantic,
        @JsonProperty("final") List<FusedResult> finalResults,
        ChunkDiagnostics diagnostics,
        Timings timings
) {

    static EvaluationSearchResponse from(SearchEvaluationResult result) {
        return new EvaluationSearchResponse(
                result.query(),
                result.mode(),
                result.semanticRetrieval(),
                SearchEvaluationParametersResponse.from(result.parameters()),
                result.lexical().stream().map(LexicalResult::from).toList(),
                result.chunks().stream().map(ChunkResult::from).toList(),
                result.semantic().stream().map(SemanticResult::from).toList(),
                result.fused().stream().map(FusedResult::from).toList(),
                ChunkDiagnostics.from(result.diagnostics()),
                Timings.from(result.timings())
        );
    }

    public record SearchEvaluationParametersResponse(
            int documentCandidateLimit,
            int chunkCandidateLimit,
            int hnswEfSearch,
            int rrfK,
            double minimumSimilarity,
            double lexicalWeight,
            double vectorWeight
    ) {
        static SearchEvaluationParametersResponse from(SearchEvaluationParameters parameters) {
            return new SearchEvaluationParametersResponse(
                    parameters.documentCandidateLimit(),
                    parameters.chunkCandidateLimit(),
                    parameters.hnswEfSearch(),
                    parameters.rrfK(),
                    parameters.minimumSimilarity(),
                    parameters.lexicalWeight(),
                    parameters.vectorWeight()
            );
        }
    }

    public record ChunkResult(UUID documentId, int chunkIndex, int rank, double similarity) {
        static ChunkResult from(com.nevis.search.application.evaluation.EvaluationSemanticSearchResult.ChunkHit hit) {
            return new ChunkResult(hit.documentId(), hit.chunkIndex(), hit.rank(), hit.similarity());
        }
    }

    public record LexicalResult(UUID documentId, int rank, double score) {
        static LexicalResult from(SearchEvaluationResult.RankedDocument result) {
            return new LexicalResult(result.document().id(), result.rank(), result.score());
        }
    }

    public record SemanticResult(UUID documentId, int rank, double similarity) {
        static SemanticResult from(SearchEvaluationResult.RankedDocument result) {
            return new SemanticResult(result.document().id(), result.rank(), result.score());
        }
    }

    public record FusedResult(
            UUID documentId,
            int rank,
            @Schema(description = "Weighted Reciprocal Rank Fusion score") double rrfScore
    ) {
        static FusedResult from(SearchEvaluationResult.RankedDocument result) {
            return new FusedResult(result.document().id(), result.rank(), result.score());
        }
    }

    public record ChunkDiagnostics(int distinctDocuments, int maximumChunksPerDocument, double concentration) {
        static ChunkDiagnostics from(SearchEvaluationResult.ChunkDiagnostics diagnostics) {
            return new ChunkDiagnostics(
                    diagnostics.distinctDocuments(), diagnostics.maximumChunksPerDocument(), diagnostics.concentration()
            );
        }
    }

    public record Timings(long lexicalMs, long semanticMs, long diagnosticsMs, long fusionMs, long totalMs) {
        static Timings from(SearchEvaluationResult.Timings timings) {
            return new Timings(
                    timings.lexicalMs(), timings.semanticMs(), timings.diagnosticsMs(),
                    timings.fusionMs(), timings.totalMs()
            );
        }
    }
}
