package com.nevis.search.application.port;

import com.nevis.search.application.embedding.EmbeddingVector;

public interface EmbeddingPort {

    EmbeddingVector embed(String text);
}
