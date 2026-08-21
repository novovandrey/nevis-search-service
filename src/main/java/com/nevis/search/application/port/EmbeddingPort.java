package com.nevis.search.application.port;

import com.nevis.search.application.embedding.EmbeddingVector;

public interface EmbeddingPort {

    EmbeddingVector embedQuery(String text);

    EmbeddingVector embedPassage(String text);
}
