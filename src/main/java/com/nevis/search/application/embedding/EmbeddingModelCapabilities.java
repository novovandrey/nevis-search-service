package com.nevis.search.application.embedding;

public record EmbeddingModelCapabilities(
        String modelId,
        int dimension,
        int maxInputTokens
) {

    public EmbeddingModelCapabilities {
        if (modelId == null || modelId.isBlank() || dimension < 1 || maxInputTokens < 1) {
            throw new IllegalArgumentException("Invalid embedding model capabilities");
        }
    }
}
