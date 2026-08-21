package com.nevis.search.domain;

import com.nevis.search.application.embedding.EmbeddingVector;

public record DocumentChunk(
        int index,
        String content,
        EmbeddingVector embedding
) {

    public DocumentChunk {
        if (index < 0 || content == null || content.isBlank() || embedding == null) {
            throw new IllegalArgumentException("Invalid document chunk");
        }
    }
}
