package com.nevis.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nevis.documents.chunking")
public record DocumentChunkingProperties(
        int maxInputTokens,
        int maxTitleTokens,
        int overlapTokens
) {

    public DocumentChunkingProperties {
        if (maxInputTokens < 1 || maxTitleTokens < 0 || maxTitleTokens >= maxInputTokens
                || overlapTokens < 0 || overlapTokens >= maxInputTokens - maxTitleTokens) {
            throw new IllegalArgumentException("Invalid nevis.documents.chunking configuration");
        }
    }
}
