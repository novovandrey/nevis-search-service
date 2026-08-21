package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.application.evaluation.EvaluationSemanticSearchResult;
import com.nevis.search.application.evaluation.EvaluationSemanticSearchResult.ChunkHit;
import com.nevis.search.application.evaluation.SemanticRetrievalMode;
import com.nevis.search.application.port.EvaluationSemanticSearchPort;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@Profile("evaluation")
public class PostgresEvaluationSemanticSearchAdapter implements EvaluationSemanticSearchPort {

    private static final String NEAREST_CHUNKS_SQL = """
            SELECT document_id,
                   chunk_index,
                   1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
            FROM document_chunks
            ORDER BY embedding <=> CAST(:embedding AS vector)
            LIMIT :chunkCandidateLimit
            """;

    private static final String COLLAPSED_DOCUMENTS_SQL = """
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
            """;

    private final JdbcClient jdbcClient;

    public PostgresEvaluationSemanticSearchAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public EvaluationSemanticSearchResult search(
            EmbeddingVector queryEmbedding,
            SemanticRetrievalMode retrievalMode,
            int documentCandidateLimit,
            int chunkCandidateLimit,
            int hnswEfSearch,
            double minimumSimilarity
    ) {
        configure(retrievalMode, hnswEfSearch);
        String vector = PostgresDocumentRepository.toVectorLiteral(queryEmbedding);

        long retrievalStartedAt = System.nanoTime();
        List<DocumentSearchResult> documents = jdbcClient.sql(COLLAPSED_DOCUMENTS_SQL)
                .param("embedding", vector)
                .param("minimumSimilarity", minimumSimilarity)
                .param("chunkCandidateLimit", chunkCandidateLimit)
                .param("documentCandidateLimit", documentCandidateLimit)
                .query((resultSet, rowNumber) -> {
                    Document document = PostgresDocumentRepository.mapDocument(resultSet, rowNumber);
                    return new DocumentSearchResult(document, resultSet.getDouble("relevance"));
                })
                .list();
        long retrievalMs = elapsedMs(retrievalStartedAt);

        long diagnosticsStartedAt = System.nanoTime();
        List<ChunkHit> chunks = jdbcClient.sql(NEAREST_CHUNKS_SQL)
                .param("embedding", vector)
                .param("chunkCandidateLimit", chunkCandidateLimit)
                .query((resultSet, rowNumber) -> new ChunkHit(
                        resultSet.getObject("document_id", java.util.UUID.class),
                        resultSet.getInt("chunk_index"),
                        rowNumber + 1,
                        resultSet.getDouble("similarity")
                ))
                .list();
        return new EvaluationSemanticSearchResult(
                documents, chunks, retrievalMs, elapsedMs(diagnosticsStartedAt)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public String explain(
            EmbeddingVector queryEmbedding,
            SemanticRetrievalMode retrievalMode,
            int chunkCandidateLimit,
            int hnswEfSearch
    ) {
        configure(retrievalMode, hnswEfSearch);
        String vector = PostgresDocumentRepository.toVectorLiteral(queryEmbedding);
        return jdbcClient.sql("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + NEAREST_CHUNKS_SQL)
                .param("embedding", vector)
                .param("chunkCandidateLimit", chunkCandidateLimit)
                .query(String.class)
                .single();
    }

    private void configure(SemanticRetrievalMode retrievalMode, int hnswEfSearch) {
        if (retrievalMode == SemanticRetrievalMode.EXACT) {
            jdbcClient.sql("SET LOCAL enable_indexscan = off").update();
            jdbcClient.sql("SET LOCAL enable_indexonlyscan = off").update();
            jdbcClient.sql("SET LOCAL enable_bitmapscan = off").update();
        } else {
            jdbcClient.sql("SET LOCAL hnsw.ef_search = " + hnswEfSearch).update();
        }
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
