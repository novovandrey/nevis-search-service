package com.nevis.search.infrastructure.embedding;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.port.EmbeddingTokenizerPort;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurposeAwareEmbeddingAdapterTest {

    @Test
    void preparesPurposeSpecificInputsForEveryEvaluationModel() {
        assertPrepared(EvaluationEmbeddingModelType.MINILM, "question", "passage", "question", "passage");
        assertPrepared(
                EvaluationEmbeddingModelType.BGE_SMALL_EN_V15,
                "question",
                "passage",
                "Represent this sentence for searching relevant passages: question",
                "passage"
        );
        assertPrepared(
                EvaluationEmbeddingModelType.E5_SMALL_V2,
                "question",
                "passage",
                "query: question",
                "passage: passage"
        );
    }

    @Test
    void rejectsPreparedInputBeyondSafeModelLimit() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        EmbeddingTokenizerPort tokenizer = new FixedTokenizer(511);
        PurposeAwareEmbeddingAdapter adapter = new PurposeAwareEmbeddingAdapter(
                model,
                tokenizer,
                new EmbeddingModelCapabilities("test", 384, 510),
                EvaluationEmbeddingModelType.E5_SMALL_V2
        );

        assertThatThrownBy(() -> adapter.embedPassage("too long"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("511 tokens")
                .hasMessageContaining("maximum");
        verify(model, never()).embed(anyString());
    }

    @Test
    void exposes384DimensionsAndSafe510TokenLimitForAllModels() {
        EvaluationEmbeddingConfiguration configuration = new EvaluationEmbeddingConfiguration();

        for (EvaluationEmbeddingModelType type : EvaluationEmbeddingModelType.values()) {
            EmbeddingModelCapabilities capabilities = configuration.embeddingModelCapabilities(type);
            assertThat(capabilities.modelId()).isEqualTo(type.modelId());
            assertThat(capabilities.dimension()).isEqualTo(384);
            assertThat(capabilities.maxInputTokens()).isEqualTo(510);
        }
    }

    @Test
    void bundledTokenizersSliceUnicodeWithoutBreakingCodePoints() {
        String text = "Café 🧭 naïve 東京 document";

        for (EvaluationEmbeddingModelType type : EvaluationEmbeddingModelType.values()) {
            try (BundledEmbeddingTokenizerAdapter tokenizer = new BundledEmbeddingTokenizerAdapter(
                    type.tokenizerResource(), type.modelId()
            )) {
                int count = tokenizer.countTokens(text);
                String sliced = tokenizer.slice(text, 0, count);
                assertThat(sliced).isEqualTo(text);
                assertThat(sliced).doesNotContain("�");
            }
        }
    }

    @Test
    void everyFullPrecisionEvaluationModelProducesFinite384DimensionalVectors() {
        EvaluationEmbeddingConfiguration configuration = new EvaluationEmbeddingConfiguration();

        for (EvaluationEmbeddingModelType type : EvaluationEmbeddingModelType.values()) {
            EmbeddingModelCapabilities capabilities = configuration.embeddingModelCapabilities(type);
            try (BundledEmbeddingTokenizerAdapter tokenizer = new BundledEmbeddingTokenizerAdapter(
                    type.tokenizerResource(), type.modelId()
            )) {
                PurposeAwareEmbeddingAdapter adapter = new PurposeAwareEmbeddingAdapter(
                        configuration.evaluationEmbeddingModel(type), tokenizer, capabilities, type
                );
                float[] query = adapter.embedQuery("evidence of a residential address").values();
                float[] passage = adapter.embedPassage("Utility statement for the home at 10 King Street").values();
                assertThat(query).hasSize(384);
                assertThat(passage).hasSize(384);
                assertThat(java.util.stream.IntStream.range(0, query.length)
                        .allMatch(index -> Float.isFinite(query[index]))).isTrue();
                assertThat(java.util.stream.IntStream.range(0, passage.length)
                        .allMatch(index -> Float.isFinite(passage[index]))).isTrue();
            }
        }
    }

    private void assertPrepared(
            EvaluationEmbeddingModelType type,
            String query,
            String passage,
            String expectedQuery,
            String expectedPassage
    ) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        float[] values = new float[384];
        Arrays.fill(values, 0.25f);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(values)));
        PurposeAwareEmbeddingAdapter adapter = new PurposeAwareEmbeddingAdapter(
                model,
                new FixedTokenizer(10),
                new EmbeddingModelCapabilities(type.modelId(), 384, 510),
                type
        );

        adapter.embedQuery(query);
        adapter.embedPassage(passage);

        ArgumentCaptor<String> inputs = ArgumentCaptor.forClass(String.class);
        verify(model, org.mockito.Mockito.times(2)).embed(inputs.capture());
        assertThat(inputs.getAllValues()).containsExactly(expectedQuery, expectedPassage);
    }

    private record FixedTokenizer(int count) implements EmbeddingTokenizerPort {
        @Override
        public int countTokens(String text) {
            return count;
        }

        @Override
        public String slice(String text, int fromTokenInclusive, int toTokenExclusive) {
            return text;
        }
    }
}
