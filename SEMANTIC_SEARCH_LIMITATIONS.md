# Semantic-search production limitations

The current service deliberately uses one local MiniLM embedding for the complete document title
and content. It does not chunk documents. Long or multi-topic documents may therefore lose passage
level relevance, and this search-quality task does not change that model.

Embeddings are generated at document creation time. The service has no embedding-model lifecycle,
embedding version, batch reindex process or migration workflow. Changing the model or its semantic
input format requires a deliberate future reindexing design; existing V1-only rows also need such
a reindex before participating in vector search.

PostgreSQL pgvector currently uses exact cosine-distance scans for the small deployment. No ANN
index, dedicated retrieval engine, cross-encoder reranking, online experimentation or user click
analytics is introduced by the evaluation work. The offline benchmark evaluates the existing
retrieval architecture; it does not make these production concerns disappear.
