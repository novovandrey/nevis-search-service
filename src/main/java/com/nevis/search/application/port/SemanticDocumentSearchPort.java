package com.nevis.search.application.port;

import com.nevis.search.domain.DocumentSearchResult;

import java.util.List;

public interface SemanticDocumentSearchPort {

    List<DocumentSearchResult> search(float[] queryEmbedding, int limit, double minimumSimilarity);
}
