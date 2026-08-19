package com.nevis.search.infrastructure.embedding;

import com.nevis.search.application.port.EmbeddingPort;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.springframework.stereotype.Component;

@Component
public class LocalMiniLmEmbeddingAdapter implements EmbeddingPort {

    public static final int DIMENSION = 384;

    private final EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();

    @Override
    public float[] embed(String text) {
        float[] embedding = embeddingModel.embed(text).content().vector();
        if (embedding.length != DIMENSION) {
            throw new IllegalStateException("Unexpected embedding dimension: " + embedding.length);
        }
        return embedding;
    }
}
