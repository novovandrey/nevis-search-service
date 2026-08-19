CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE documents
    ADD COLUMN embedding vector(384);

ALTER TABLE documents
    ADD CONSTRAINT documents_embedding_required CHECK (embedding IS NOT NULL) NOT VALID;
