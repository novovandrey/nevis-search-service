package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.application.port.DocumentRepository;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class PostgresDocumentRepository implements DocumentRepository {

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    public PostgresDocumentRepository(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Document save(Document document, List<DocumentChunk> chunks) {
        jdbcClient.sql("""
                        INSERT INTO documents (id, client_id, title, content, created_at)
                        VALUES (:id, :clientId, :title, :content, :createdAt)
                        """)
                .param("id", document.id())
                .param("clientId", document.clientId())
                .param("title", document.title())
                .param("content", document.content())
                .param("createdAt", Timestamp.from(document.createdAt()))
                .update();
        jdbcTemplate.batchUpdate("""
                        INSERT INTO document_chunks (document_id, chunk_index, content, embedding)
                        VALUES (?, ?, ?, CAST(? AS vector))
                        """,
                chunks,
                chunks.size(),
                (statement, chunk) -> {
                    statement.setObject(1, document.id());
                    statement.setInt(2, chunk.index());
                    statement.setString(3, chunk.content());
                    statement.setString(4, toVectorLiteral(chunk.embedding()));
                });
        return document;
    }

    static Document mapDocument(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Document(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("client_id", UUID.class),
                resultSet.getString("title"),
                resultSet.getString("content"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    static String toVectorLiteral(EmbeddingVector embedding) {
        float[] values = embedding.values();
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(values[index]);
        }
        return literal.append(']').toString();
    }
}
