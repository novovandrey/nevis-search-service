package com.nevis.search.application.evaluation;

import com.nevis.search.application.evaluation.SearchEvaluationOverrides.SearchEvaluationParameters;
import com.nevis.search.domain.Document;

import java.util.List;

public record SearchEvaluationResult(
        String query,
        SearchEvaluationMode mode,
        SearchEvaluationParameters parameters,
        List<RankedDocument> lexical,
        List<RankedDocument> semantic,
        List<RankedDocument> fused,
        Timings timings
) {

    public record RankedDocument(Document document, int rank, double score) {
    }

    public record Timings(long lexicalMs, long semanticMs, long fusionMs, long totalMs) {
    }
}
