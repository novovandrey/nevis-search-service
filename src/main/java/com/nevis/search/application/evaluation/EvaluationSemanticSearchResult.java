package com.nevis.search.application.evaluation;

import com.nevis.search.domain.DocumentSearchResult;

import java.util.List;
import java.util.UUID;

public record EvaluationSemanticSearchResult(
        List<DocumentSearchResult> documents,
        List<ChunkHit> chunks,
        long retrievalMs,
        long diagnosticsMs
) {

    public record ChunkHit(UUID documentId, int chunkIndex, int rank, double similarity) {
    }
}
