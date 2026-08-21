package com.nevis.search.application.evaluation;

import com.nevis.search.application.HybridDocumentSearchMerger;
import com.nevis.search.config.SemanticSearchProperties;

public record SearchEvaluationOverrides(
        Integer documentCandidateLimit,
        Integer chunkCandidateLimit,
        Integer hnswEfSearch,
        Integer rrfK,
        Double minimumSimilarity,
        Double lexicalWeight,
        Double vectorWeight
) {

    public SearchEvaluationParameters resolve(SemanticSearchProperties defaults) {
        return new SearchEvaluationParameters(
                documentCandidateLimit == null ? defaults.candidateLimit() : documentCandidateLimit,
                chunkCandidateLimit == null ? defaults.chunkCandidateLimit() : chunkCandidateLimit,
                hnswEfSearch == null ? defaults.hnswEfSearch() : hnswEfSearch,
                rrfK == null ? defaults.rrfK() : rrfK,
                minimumSimilarity == null ? defaults.minimumSimilarity() : minimumSimilarity,
                lexicalWeight == null ? defaults.lexicalWeight() : lexicalWeight,
                vectorWeight == null ? defaults.vectorWeight() : vectorWeight
        );
    }

    public record SearchEvaluationParameters(
            int documentCandidateLimit,
            int chunkCandidateLimit,
            int hnswEfSearch,
            int rrfK,
            double minimumSimilarity,
            double lexicalWeight,
            double vectorWeight
    ) {

        public SearchEvaluationParameters {
            if (documentCandidateLimit < 1 || chunkCandidateLimit < documentCandidateLimit
                    || hnswEfSearch < chunkCandidateLimit || rrfK < 1 || !Double.isFinite(minimumSimilarity)
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
