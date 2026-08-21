package com.nevis.search.application.evaluation;

import com.nevis.search.application.evaluation.SearchEvaluationOverrides.SearchEvaluationParameters;
import com.nevis.search.domain.Document;

import java.util.List;

public record SearchEvaluationResult(
        String query,
        SearchEvaluationMode mode,
        SemanticRetrievalMode semanticRetrieval,
        SearchEvaluationParameters parameters,
        List<RankedDocument> lexical,
        List<EvaluationSemanticSearchResult.ChunkHit> chunks,
        List<RankedDocument> semantic,
        List<RankedDocument> fused,
        ChunkDiagnostics diagnostics,
        Timings timings
) {

    public record RankedDocument(Document document, int rank, double score) {
    }

    public record ChunkDiagnostics(int distinctDocuments, int maximumChunksPerDocument, double concentration) {
    }

    public record Timings(long lexicalMs, long semanticMs, long diagnosticsMs, long fusionMs, long totalMs) {
    }
}
