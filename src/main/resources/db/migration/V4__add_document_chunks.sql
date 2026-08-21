CREATE TABLE document_chunks (
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL,
    embedding vector(384) NOT NULL,
    PRIMARY KEY (document_id, chunk_index)
);

CREATE INDEX document_chunks_embedding_hnsw_idx
    ON document_chunks
    USING hnsw (embedding vector_cosine_ops);

ALTER TABLE documents
    DROP CONSTRAINT IF EXISTS documents_embedding_required;

ALTER TABLE documents
    DROP COLUMN embedding;
