package com.nevis.search.application.port;

import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.application.evaluation.EvaluationSemanticSearchResult;
import com.nevis.search.application.evaluation.SemanticRetrievalMode;

public interface EvaluationSemanticSearchPort {

    EvaluationSemanticSearchResult search(
            EmbeddingVector queryEmbedding,
            SemanticRetrievalMode retrievalMode,
            int documentCandidateLimit,
            int chunkCandidateLimit,
            int hnswEfSearch,
            double minimumSimilarity
    );

    String explain(
            EmbeddingVector queryEmbedding,
            SemanticRetrievalMode retrievalMode,
            int chunkCandidateLimit,
            int hnswEfSearch
    );
}
