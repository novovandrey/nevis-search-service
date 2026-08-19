package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PostgresSemanticDocumentSearchAdapter implements SemanticDocumentSearchPort {

    private final JdbcClient jdbcClient;

    public PostgresSemanticDocumentSearchAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<DocumentSearchResult> search(float[] queryEmbedding, int limit, double minimumSimilarity) {
        if (limit < 1) {
            return List.of();
        }

        String vector = PostgresDocumentRepository.toVectorLiteral(queryEmbedding);
        return jdbcClient.sql("""
                        SELECT d.id, d.client_id, d.title, d.content, d.created_at,
                               1 - (d.embedding <=> CAST(:embedding AS vector)) AS relevance
                        FROM documents d
                        WHERE d.embedding IS NOT NULL
                          AND 1 - (d.embedding <=> CAST(:embedding AS vector)) >= :minimumSimilarity
                        ORDER BY d.embedding <=> CAST(:embedding AS vector), d.created_at DESC, d.id
                        LIMIT :limit
                        """)
                .param("embedding", vector)
                .param("minimumSimilarity", minimumSimilarity)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    Document document = PostgresDocumentRepository.mapDocument(resultSet, rowNumber);
                    return new DocumentSearchResult(document, resultSet.getDouble("relevance"));
                })
                .list();
    }
}
