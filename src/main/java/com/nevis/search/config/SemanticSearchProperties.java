package com.nevis.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nevis.search.semantic")
public record SemanticSearchProperties(int candidateLimit, int rrfK, double minimumSimilarity) {

    public SemanticSearchProperties {
        if (candidateLimit < 1 || rrfK < 1 || !Double.isFinite(minimumSimilarity)
                || minimumSimilarity < -1 || minimumSimilarity > 1) {
            throw new IllegalArgumentException("Invalid nevis.search.semantic configuration");
        }
    }
}
