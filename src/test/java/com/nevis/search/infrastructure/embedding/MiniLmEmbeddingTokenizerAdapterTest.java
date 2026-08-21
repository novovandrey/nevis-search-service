package com.nevis.search.infrastructure.embedding;

import com.nevis.search.application.DocumentChunker;
import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.config.DocumentChunkingProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MiniLmEmbeddingTokenizerAdapterTest {

    @Test
    void usesModelCapabilitiesAndSlicesUnicodeOnTokenBoundaries() {
        EmbeddingModelCapabilities capabilities = new EmbeddingConfiguration().embeddingModelCapabilities();
        MiniLmEmbeddingTokenizerAdapter tokenizer = new MiniLmEmbeddingTokenizerAdapter();
        try {
            String text = "Привет 🌍 café 東京 résumé naïve façade";
            int tokens = tokenizer.countTokens(text);

            assertThat(capabilities.modelId()).isEqualTo("all-MiniLM-L6-v2");
            assertThat(capabilities.dimension()).isEqualTo(384);
            assertThat(capabilities.maxInputTokens()).isEqualTo(510);
            assertThat(tokens).isPositive();
            for (int index = 0; index < tokens; index++) {
                String slice = tokenizer.slice(text, index, index + 1);
                assertThat(slice).isNotEmpty().doesNotContain("�");
                assertThat(Character.isHighSurrogate(slice.charAt(slice.length() - 1))).isFalse();
                assertThat(Character.isLowSurrogate(slice.charAt(0))).isFalse();
            }

            DocumentChunker chunker = new DocumentChunker(
                    tokenizer, new DocumentChunkingProperties(240, 32, 30), capabilities
            );
            String longTitle = "International household compliance archive ".repeat(30);
            var chunks = chunker.chunk(
                    longTitle,
                    "Paragraph one contains multilingual text Привет 🌍 café 東京. ".repeat(200)
            );
            assertThat(tokenizer.countTokens(chunker.truncateTitle(longTitle))).isLessThanOrEqualTo(32);
            assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
                assertThat(tokenizer.countTokens(chunk.embeddingInput())).isLessThanOrEqualTo(240);
                assertThat(chunk.body()).doesNotContain("�");
            });
        } finally {
            tokenizer.close();
        }
    }
}
