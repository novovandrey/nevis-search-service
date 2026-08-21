package com.nevis.search.infrastructure.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import com.nevis.search.application.port.EmbeddingTokenizerPort;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Component
public class MiniLmEmbeddingTokenizerAdapter implements EmbeddingTokenizerPort {

    private final HuggingFaceTokenizer tokenizer;

    public MiniLmEmbeddingTokenizerAdapter() {
        try (InputStream input = getClass().getResourceAsStream("/all-minilm-l6-v2-tokenizer.json")) {
            if (input == null) {
                throw new IllegalStateException("MiniLM tokenizer resource is unavailable");
            }
            tokenizer = HuggingFaceTokenizer.newInstance(
                    input, Map.of("padding", "false", "truncation", "false")
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize MiniLM tokenizer", exception);
        }
    }

    @Override
    public int countTokens(String text) {
        return encode(text).getTokens().length;
    }

    @Override
    public String slice(String text, int fromTokenInclusive, int toTokenExclusive) {
        Encoding encoding = encode(text);
        CharSpan[] spans = encoding.getCharTokenSpans();
        if (fromTokenInclusive < 0 || toTokenExclusive < fromTokenInclusive || toTokenExclusive > spans.length) {
            throw new IllegalArgumentException("Invalid token slice");
        }
        if (fromTokenInclusive == toTokenExclusive) {
            return "";
        }
        int startCodePoint = spans[fromTokenInclusive].getStart();
        int endCodePoint = spans[toTokenExclusive - 1].getEnd();
        int codePointCount = text.codePointCount(0, text.length());
        if (startCodePoint < 0 || endCodePoint < startCodePoint || endCodePoint > codePointCount) {
            throw new IllegalStateException("Tokenizer returned invalid character offsets");
        }
        int start = text.offsetByCodePoints(0, startCodePoint);
        int end = text.offsetByCodePoints(0, endCodePoint);
        return text.substring(start, end);
    }

    private Encoding encode(String text) {
        return tokenizer.encode(text, false, true);
    }

    @PreDestroy
    void close() {
        tokenizer.close();
    }
}
