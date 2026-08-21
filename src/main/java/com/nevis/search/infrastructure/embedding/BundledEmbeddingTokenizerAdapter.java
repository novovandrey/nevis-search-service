package com.nevis.search.infrastructure.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.jni.CharSpan;
import com.nevis.search.application.port.EmbeddingTokenizerPort;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

final class BundledEmbeddingTokenizerAdapter implements EmbeddingTokenizerPort, AutoCloseable {

    private final HuggingFaceTokenizer tokenizer;

    BundledEmbeddingTokenizerAdapter(String resource, String modelId) {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(modelId + " tokenizer resource is unavailable");
            }
            tokenizer = HuggingFaceTokenizer.newInstance(
                    input, Map.of("padding", "false", "truncation", "false")
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize " + modelId + " tokenizer", exception);
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
        return text.substring(
                text.offsetByCodePoints(0, startCodePoint),
                text.offsetByCodePoints(0, endCodePoint)
        );
    }

    private Encoding encode(String text) {
        return tokenizer.encode(text, false, true);
    }

    @Override
    public void close() {
        tokenizer.close();
    }
}
