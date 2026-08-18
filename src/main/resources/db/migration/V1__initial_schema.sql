CREATE TABLE clients (
    id UUID PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    country_of_residence VARCHAR(100)
);

CREATE TABLE documents (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL REFERENCES clients(id),
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('english', coalesce(content, '')), 'B')
    ) STORED
);

CREATE INDEX documents_search_vector_idx ON documents USING GIN (search_vector);
CREATE INDEX documents_client_id_idx ON documents (client_id);

CREATE TABLE search_term_mapping (
    group_key VARCHAR(100) NOT NULL,
    term VARCHAR(200) NOT NULL,
    PRIMARY KEY (group_key, term)
);

INSERT INTO search_term_mapping (group_key, term) VALUES
    ('proof_of_address', 'address proof'),
    ('proof_of_address', 'proof of address'),
    ('proof_of_address', 'proof of residency'),
    ('proof_of_address', 'utility bill'),
    ('proof_of_address', 'bank statement');
