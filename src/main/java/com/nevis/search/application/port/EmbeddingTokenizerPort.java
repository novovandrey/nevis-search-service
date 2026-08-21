package com.nevis.search.application.port;

public interface EmbeddingTokenizerPort {

    int countTokens(String text);

    String slice(String text, int fromTokenInclusive, int toTokenExclusive);
}
