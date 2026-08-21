package com.nevis.search.application.embedding;

import java.util.Arrays;

public final class EmbeddingVector {

    private final float[] values;

    private EmbeddingVector(float[] values) {
        this.values = values;
    }

    public static EmbeddingVector of(float[] values, EmbeddingModelCapabilities capabilities) {
        if (values == null || values.length != capabilities.dimension()) {
            throw new IllegalArgumentException(
                    "Embedding must have " + capabilities.dimension() + " dimensions"
            );
        }
        float[] copy = values.clone();
        for (float value : copy) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding must contain only finite values");
            }
        }
        return new EmbeddingVector(copy);
    }

    public int dimension() {
        return values.length;
    }

    public float[] values() {
        return values.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EmbeddingVector vector && Arrays.equals(values, vector.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
