package com.nevis.search.infrastructure.embedding;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.EmbeddingTokenizerPort;
import dev.langchain4j.model.embedding.EmbeddingModel;

final class PurposeAwareEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel model;
    private final EmbeddingTokenizerPort tokenizer;
    private final EmbeddingModelCapabilities capabilities;
    private final EvaluationEmbeddingModelType modelType;

    PurposeAwareEmbeddingAdapter(
            EmbeddingModel model,
            EmbeddingTokenizerPort tokenizer,
            EmbeddingModelCapabilities capabilities,
            EvaluationEmbeddingModelType modelType
    ) {
        this.model = model;
        this.tokenizer = tokenizer;
        this.capabilities = capabilities;
        this.modelType = modelType;
    }

    @Override
    public EmbeddingVector embedQuery(String text) {
        return embed(modelType.prepareQuery(text));
    }

    @Override
    public EmbeddingVector embedPassage(String text) {
        return embed(modelType.preparePassage(text));
    }

    private EmbeddingVector embed(String preparedText) {
        int tokens = tokenizer.countTokens(preparedText);
        if (tokens > capabilities.maxInputTokens()) {
            throw new IllegalArgumentException(
                    "Prepared embedding input has %d tokens; maximum for %s is %d"
                            .formatted(tokens, capabilities.modelId(), capabilities.maxInputTokens())
            );
        }
        return EmbeddingVector.of(model.embed(preparedText).content().vector(), capabilities);
    }
}
