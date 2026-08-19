CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE clients
    ADD COLUMN company_search_key TEXT GENERATED ALWAYS AS (
        CASE
            WHEN lower(email) ~ '^[^@[:space:]]+@[^@.[:space:]]+(\.[^@.[:space:]]+)+$'
                THEN regexp_replace(
                    split_part(lower(email), '@', 2),
                    '\.[^.]+$',
                    ''
                )
            ELSE NULL
        END
    ) STORED;

CREATE INDEX clients_company_search_key_exact_idx
    ON clients (company_search_key)
    WHERE company_search_key IS NOT NULL;

CREATE INDEX clients_company_search_key_trgm_idx
    ON clients USING GIN (company_search_key gin_trgm_ops)
    WHERE company_search_key IS NOT NULL;
