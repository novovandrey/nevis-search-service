package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.evaluation.EvaluationDatabaseMetadata;
import com.nevis.search.application.port.EvaluationMetadataPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("evaluation")
public class PostgresEvaluationMetadataAdapter implements EvaluationMetadataPort {

    private final JdbcClient jdbcClient;

    public PostgresEvaluationMetadataAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public EvaluationDatabaseMetadata read() {
        return jdbcClient.sql("""
                        SELECT current_setting('server_version') AS postgres_version,
                               (SELECT extversion FROM pg_extension WHERE extname = 'vector') AS pgvector_version,
                               (SELECT count(*) FROM documents) AS document_count,
                               (SELECT count(*) FROM document_chunks) AS chunk_count,
                               pg_table_size('document_chunks') AS chunk_table_bytes,
                               COALESCE(pg_relation_size(
                                   to_regclass('document_chunks_embedding_hnsw_idx')
                               ), 0) AS hnsw_index_bytes
                        """)
                .query((resultSet, rowNumber) -> new EvaluationDatabaseMetadata(
                        resultSet.getString("postgres_version"),
                        resultSet.getString("pgvector_version"),
                        resultSet.getLong("document_count"),
                        resultSet.getLong("chunk_count"),
                        resultSet.getLong("chunk_table_bytes"),
                        resultSet.getLong("hnsw_index_bytes")
                ))
                .single();
    }
}
