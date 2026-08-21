package com.nevis.search.application.evaluation;

public record EvaluationDatabaseMetadata(
        String postgresVersion,
        String pgvectorVersion,
        long documentCount,
        long chunkCount,
        long chunkTableBytes,
        long hnswIndexBytes
) {
}
