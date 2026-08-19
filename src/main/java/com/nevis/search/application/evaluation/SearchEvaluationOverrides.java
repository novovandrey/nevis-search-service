package com.nevis.search.application.evaluation;

import com.nevis.search.application.HybridDocumentSearchMerger;
import com.nevis.search.config.SemanticSearchProperties;

public record SearchEvaluationOverrides(
        Integer candidateLimit,
        Integer rrfK,
        Double minimumSimilarity,
        Double lexicalWeight,
        Double vectorWeight
) {

    public SearchEvaluationParameters resolve(SemanticSearchProperties defaults) {
        return new SearchEvaluationParameters(
                candidateLimit == null ? defaults.candidateLimit() : candidateLimit,
                rrfK == null ? defaults.rrfK() : rrfK,
                minimumSimilarity == null ? defaults.minimumSimilarity() : minimumSimilarity,
                lexicalWeight == null ? defaults.lexicalWeight() : lexicalWeight,
                vectorWeight == null ? defaults.vectorWeight() : vectorWeight
        );
    }

    public record SearchEvaluationParameters(
            int candidateLimit,
            int rrfK,
            double minimumSimilarity,
            double lexicalWeight,
            double vectorWeight
    ) {

        public SearchEvaluationParameters {
            if (candidateLimit < 1 || rrfK < 1 || !Double.isFinite(minimumSimilarity)
                    || minimumSimilarity < -1 || minimumSimilarity > 1
                    || !Double.isFinite(lexicalWeight) || lexicalWeight <= 0
                    || !Double.isFinite(vectorWeight) || vectorWeight <= 0) {
                throw new IllegalArgumentException("Invalid evaluation search parameters");
            }
        }

        public HybridDocumentSearchMerger.HybridSearchConfiguration hybridConfiguration() {
            return new HybridDocumentSearchMerger.HybridSearchConfiguration(rrfK, lexicalWeight, vectorWeight);
        }
    }
}
