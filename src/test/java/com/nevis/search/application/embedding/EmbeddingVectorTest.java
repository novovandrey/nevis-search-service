package com.nevis.search.application.embedding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingVectorTest {

    private final EmbeddingModelCapabilities capabilities = new EmbeddingModelCapabilities("test", 3, 10);

    @Test
    void validatesDimensionFiniteValuesAndDefensivelyCopies() {
        float[] source = {1, 2, 3};
        EmbeddingVector vector = EmbeddingVector.of(source, capabilities);
        source[0] = 9;
        float[] returned = vector.values();
        returned[1] = 9;

        assertThat(vector.values()).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> EmbeddingVector.of(new float[2], capabilities))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3 dimensions");
        assertThatThrownBy(() -> EmbeddingVector.of(new float[]{1, Float.NaN, 3}, capabilities))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }
}
