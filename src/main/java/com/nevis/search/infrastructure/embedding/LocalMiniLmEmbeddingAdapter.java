package com.nevis.search.infrastructure.embedding;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.application.port.EmbeddingPort;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!evaluation")
public class LocalMiniLmEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
    private final EmbeddingModelCapabilities capabilities;

    public LocalMiniLmEmbeddingAdapter(EmbeddingModelCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    @Override
    public EmbeddingVector embedQuery(String text) {
        return embed(text);
    }

    @Override
    public EmbeddingVector embedPassage(String text) {
        return embed(text);
    }

    private EmbeddingVector embed(String text) {
        float[] embedding = embeddingModel.embed(text).content().vector();
        return EmbeddingVector.of(embedding, capabilities);
    }
}
