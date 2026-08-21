package com.nevis.search.infrastructure.embedding;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMiniLmEmbeddingAdapterTest {

    @Test
    void embedsConceptuallyRelatedTextCloserThanAnUnrelatedDocument() {
        EmbeddingModelCapabilities capabilities = new EmbeddingModelCapabilities(
                "all-MiniLM-L6-v2", 384, 510
        );
        LocalMiniLmEmbeddingAdapter embeddings = new LocalMiniLmEmbeddingAdapter(capabilities);

        float[] query = embeddings.embed("evidence of where the customer lives").values();
        float[] residenceEvidence = embeddings.embed("""
                Monthly statement

                The customer receives a monthly electricity statement for the apartment at 10 King Street.
                """).values();
        float[] recipe = embeddings.embed("""
                Cooking notes

                The recipe uses olive oil, tomatoes, basil and pasta for dinner.
                """).values();

        assertThat(query).hasSize(capabilities.dimension());
        assertThat(cosine(query, residenceEvidence))
                .isGreaterThan(0.30)
                .isGreaterThan(cosine(query, recipe));
        assertThat(cosine(
                embeddings.embed("zzzx qqqv non-existent search token").values(), residenceEvidence
        ))
                .isLessThan(0.30);
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
