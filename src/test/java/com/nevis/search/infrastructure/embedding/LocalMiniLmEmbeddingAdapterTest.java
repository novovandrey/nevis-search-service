package com.nevis.search.infrastructure.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMiniLmEmbeddingAdapterTest {

    @Test
    void embedsConceptuallyRelatedTextCloserThanAnUnrelatedDocument() {
        LocalMiniLmEmbeddingAdapter embeddings = new LocalMiniLmEmbeddingAdapter();

        float[] query = embeddings.embed("evidence showing where the person lives");
        float[] residenceEvidence = embeddings.embed("""
                Monthly statement

                The tenant receives monthly electricity statements for the apartment at 10 King Street.
                """);
        float[] recipe = embeddings.embed("""
                Cooking notes

                The recipe uses olive oil, tomatoes, basil and pasta for dinner.
                """);

        assertThat(query).hasSize(LocalMiniLmEmbeddingAdapter.DIMENSION);
        assertThat(cosine(query, residenceEvidence))
                .isGreaterThan(0.15)
                .isGreaterThan(cosine(query, recipe));
        assertThat(cosine(embeddings.embed("zzzx qqqv non-existent search token"), residenceEvidence))
                .isLessThan(0.15);
    }

    private double cosine(float[] left, float[] right) {
        double dotProduct = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int index = 0; index < left.length; index++) {
            dotProduct += left[index] * right[index];
            leftLength += left[index] * left[index];
            rightLength += right[index] * right[index];
        }
        return dotProduct / Math.sqrt(leftLength * rightLength);
    }
}
