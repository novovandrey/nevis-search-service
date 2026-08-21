package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class PostgresSemanticDocumentSearchAdapter implements SemanticDocumentSearchPort {

    private final JdbcClient jdbcClient;

    public PostgresSemanticDocumentSearchAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentSearchResult> search(
            EmbeddingVector queryEmbedding,
            int documentCandidateLimit,
            int chunkCandidateLimit,
            int hnswEfSearch,
            double minimumSimilarity
    ) {
        if (documentCandidateLimit < 1 || chunkCandidateLimit < 1) {
            return List.of();
        }

        String vector = PostgresDocumentRepository.toVectorLiteral(queryEmbedding);
        jdbcClient.sql("SET LOCAL hnsw.ef_search = " + hnswEfSearch).update();
        return jdbcClient.sql("""
                        WITH nearest_chunks AS MATERIALIZED (
                            SELECT document_id,
                                   1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
                            FROM document_chunks
                            ORDER BY embedding <=> CAST(:embedding AS vector)
                            LIMIT :chunkCandidateLimit
                        ),
                        document_scores AS (
                            SELECT document_id, MAX(similarity) AS relevance
                            FROM nearest_chunks
                            GROUP BY document_id
                        )
                        SELECT d.id, d.client_id, d.title, d.content, d.created_at, scores.relevance
                        FROM document_scores scores
                        JOIN documents d ON d.id = scores.document_id
                        WHERE scores.relevance >= :minimumSimilarity
                        ORDER BY scores.relevance DESC, d.created_at DESC, d.id
                        LIMIT :documentCandidateLimit
                        """)
                .param("embedding", vector)
                .param("minimumSimilarity", minimumSimilarity)
                .param("chunkCandidateLimit", chunkCandidateLimit)
                .param("documentCandidateLimit", documentCandidateLimit)
                .query((resultSet, rowNumber) -> {
                    Document document = PostgresDocumentRepository.mapDocument(resultSet, rowNumber);
                    return new DocumentSearchResult(document, resultSet.getDouble("relevance"));
                })
                .list();
    }
}
