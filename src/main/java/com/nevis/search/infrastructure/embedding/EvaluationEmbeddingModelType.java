package com.nevis.search.infrastructure.embedding;

enum EvaluationEmbeddingModelType {
    MINILM(
            "all-MiniLM-L6-v2",
            "/all-minilm-l6-v2-tokenizer.json",
            "",
            ""
    ),
    BGE_SMALL_EN_V15(
            "BAAI/bge-small-en-v1.5",
            "/bge-small-en-v1.5-tokenizer.json",
            "Represent this sentence for searching relevant passages: ",
            ""
    ),
    E5_SMALL_V2(
            "intfloat/e5-small-v2",
            "/e5-small-v2-tokenizer.json",
            "query: ",
            "passage: "
    );

    private final String modelId;
    private final String tokenizerResource;
    private final String queryPrefix;
    private final String passagePrefix;

    EvaluationEmbeddingModelType(
            String modelId,
            String tokenizerResource,
            String queryPrefix,
            String passagePrefix
    ) {
        this.modelId = modelId;
        this.tokenizerResource = tokenizerResource;
        this.queryPrefix = queryPrefix;
        this.passagePrefix = passagePrefix;
    }

    String modelId() {
        return modelId;
    }

    String tokenizerResource() {
        return tokenizerResource;
    }

    String prepareQuery(String text) {
        return queryPrefix + text;
    }

    String preparePassage(String text) {
        return passagePrefix + text;
    }
}
