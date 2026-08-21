package com.nevis.search.infrastructure.embedding;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.EmbeddingTokenizerPort;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15.BgeSmallEnV15EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.e5smallv2.E5SmallV2EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Locale;

@Configuration
@Profile("evaluation")
class EvaluationEmbeddingConfiguration {

    @Bean
    EvaluationEmbeddingModelType evaluationEmbeddingModelType(
            @Value("${nevis.evaluation.embedding-model:MINILM}") String configuredModel
    ) {
        try {
            return EvaluationEmbeddingModelType.valueOf(configuredModel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Unsupported evaluation embedding model: " + configuredModel, exception);
        }
    }

    @Bean
    EmbeddingModelCapabilities embeddingModelCapabilities(EvaluationEmbeddingModelType modelType) {
        return new EmbeddingModelCapabilities(modelType.modelId(), 384, 510);
    }

    @Bean
    EmbeddingModel evaluationEmbeddingModel(EvaluationEmbeddingModelType modelType) {
        return switch (modelType) {
            case MINILM -> new AllMiniLmL6V2EmbeddingModel();
            case BGE_SMALL_EN_V15 -> new BgeSmallEnV15EmbeddingModel();
            case E5_SMALL_V2 -> new E5SmallV2EmbeddingModel();
        };
    }

    @Bean(destroyMethod = "close")
    EmbeddingTokenizerPort embeddingTokenizerPort(EvaluationEmbeddingModelType modelType) {
        return new BundledEmbeddingTokenizerAdapter(modelType.tokenizerResource(), modelType.modelId());
    }

    @Bean
    EmbeddingPort embeddingPort(
            EmbeddingModel model,
            EmbeddingTokenizerPort tokenizer,
            EmbeddingModelCapabilities capabilities,
            EvaluationEmbeddingModelType modelType
    ) {
        return new PurposeAwareEmbeddingAdapter(model, tokenizer, capabilities, modelType);
    }
}
