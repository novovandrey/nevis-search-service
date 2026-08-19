package com.nevis.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nevis.search.semantic")
public record SemanticSearchProperties(
        int candidateLimit,
        int rrfK,
        double minimumSimilarity,
        double lexicalWeight,
        double vectorWeight
) {

    public SemanticSearchProperties {
        if (candidateLimit < 1 || rrfK < 1 || !Double.isFinite(minimumSimilarity)
                || minimumSimilarity < -1 || minimumSimilarity > 1
                || !Double.isFinite(lexicalWeight) || lexicalWeight <= 0
                || !Double.isFinite(vectorWeight) || vectorWeight <= 0) {
            throw new IllegalArgumentException("Invalid nevis.search.semantic configuration");
        }
    }
}
