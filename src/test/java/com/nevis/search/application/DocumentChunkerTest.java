package com.nevis.search.application;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.port.EmbeddingTokenizerPort;
import com.nevis.search.config.DocumentChunkingProperties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentChunkerTest {

    private final WhitespaceTokenizer tokenizer = new WhitespaceTokenizer();

    @Test
    void shortTextAndSmallParagraphsProduceOneChunk() {
        DocumentChunker chunker = chunker(12, 2, 2);

        assertThat(chunker.chunk("Title", "short body"))
                .extracting(DocumentChunker.Chunk::body)
                .containsExactly("short body");
        assertThat(chunker.chunk("Title", "one two\n\nthree four"))
                .extracting(DocumentChunker.Chunk::body)
                .containsExactly("one two\n\nthree four");
    }

    @Test
    void paragraphOverflowStartsANewChunkWithTokenOverlap() {
        DocumentChunker chunker = chunker(8, 2, 2);

        List<DocumentChunker.Chunk> chunks = chunker.chunk(
                "Title", "one two three four\n\nfive six seven eight"
        );

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().body()).isEqualTo("one two three four");
        assertThat(chunks.getLast().body()).isEqualTo("three four\n\nfive six seven eight");
    }

    @Test
    void oversizedParagraphUsesSentenceBoundariesBeforeTokenFallback() {
        DocumentChunker chunker = chunker(8, 2, 2);

        List<DocumentChunker.Chunk> chunks = chunker.chunk(
                "Title", "One two three four. Five six seven eight."
        );

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().body()).isEqualTo("One two three four.");
        assertThat(chunks.getLast().body())
                .startsWith("three four.")
                .endsWith("Five six seven eight.");
    }

    @Test
    void oversizedSentenceUsesTokenWindowsWithConfiguredOverlap() {
        DocumentChunker chunker = chunker(8, 2, 2);
        String body = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen";

        List<DocumentChunker.Chunk> chunks = chunker.chunk("Title", body);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks.get(1).body()).startsWith("four five\n\nsix seven");
        for (DocumentChunker.Chunk chunk : chunks) {
            assertThat(tokenizer.countTokens(chunk.embeddingInput())).isLessThanOrEqualTo(8);
        }
    }

    @Test
    void titleIsCappedAndEveryChunkHasDeterministicContiguousIndex() {
        DocumentChunker chunker = chunker(8, 2, 2);
        String body = "one two three four five six seven eight nine ten eleven twelve";

        List<DocumentChunker.Chunk> first = chunker.chunk("alpha beta gamma delta", body);
        List<DocumentChunker.Chunk> second = chunker.chunk("alpha beta gamma delta", body);

        assertThat(first).isEqualTo(second);
        assertThat(first).extracting(DocumentChunker.Chunk::index)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, first.size()).boxed().toList());
        assertThat(first).allSatisfy(chunk -> {
            assertThat(chunk.body()).isNotBlank();
            assertThat(chunk.embeddingInput()).startsWith("alpha beta\n\n");
            assertThat(tokenizer.countTokens(chunk.embeddingInput())).isLessThanOrEqualTo(8);
        });
    }

    @Test
    void unicodeTextIsNeverSplitInsideCharacters() {
        DocumentChunker chunker = chunker(6, 1, 1);
        String body = "Привет 🌍 café 東京 résumé naïve façade";

        List<DocumentChunker.Chunk> chunks = chunker.chunk("Заголовок", body);

        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.body()).doesNotContain("�");
            assertThat(Character.isHighSurrogate(chunk.body().charAt(chunk.body().length() - 1))).isFalse();
            assertThat(Character.isLowSurrogate(chunk.body().charAt(0))).isFalse();
        });
    }

    @Test
    void rejectsImpossibleConfigurationAndModelLimitMismatch() {
        assertThatThrownBy(() -> new DocumentChunkingProperties(10, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DocumentChunker(
                tokenizer,
                new DocumentChunkingProperties(511, 32, 30),
                new EmbeddingModelCapabilities("test", 384, 510)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds model capability");
        assertThatThrownBy(() -> new DocumentChunker(
                tokenizer,
                new DocumentChunkingProperties(40, 32, 8),
                new EmbeddingModelCapabilities("test", 384, 510)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private DocumentChunker chunker(int maxInputTokens, int maxTitleTokens, int overlapTokens) {
        return new DocumentChunker(
                tokenizer,
                new DocumentChunkingProperties(maxInputTokens, maxTitleTokens, overlapTokens),
                new EmbeddingModelCapabilities("test", 384, 510)
        );
    }

    private static final class WhitespaceTokenizer implements EmbeddingTokenizerPort {

        @Override
        public int countTokens(String text) {
            return tokens(text).size();
        }

        @Override
        public String slice(String text, int fromTokenInclusive, int toTokenExclusive) {
            return String.join(" ", tokens(text).subList(fromTokenInclusive, toTokenExclusive));
        }

        private List<String> tokens(String text) {
            return text.isBlank() ? List.of() : Arrays.asList(text.strip().split("\\s+"));
        }
    }
}
