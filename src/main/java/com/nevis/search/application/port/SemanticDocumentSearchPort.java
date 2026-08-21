package com.nevis.search.application.port;

import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.domain.DocumentSearchResult;

import java.util.List;

public interface SemanticDocumentSearchPort {

    List<DocumentSearchResult> search(
            EmbeddingVector queryEmbedding,
            int documentCandidateLimit,
            int chunkCandidateLimit,
            int hnswEfSearch,
            double minimumSimilarity
    );
}
