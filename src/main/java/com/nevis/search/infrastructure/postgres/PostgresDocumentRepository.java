package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.port.DocumentRepository;
import com.nevis.search.domain.Document;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class PostgresDocumentRepository implements DocumentRepository {

    private static final int EMBEDDING_DIMENSION = 384;

    private final JdbcClient jdbcClient;

    public PostgresDocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Document save(Document document, float[] embedding) {
        jdbcClient.sql("""
                        INSERT INTO documents (id, client_id, title, content, created_at, embedding)
                        VALUES (:id, :clientId, :title, :content, :createdAt, CAST(:embedding AS vector))
                        """)
                .param("id", document.id())
                .param("clientId", document.clientId())
                .param("title", document.title())
                .param("content", document.content())
                .param("createdAt", Timestamp.from(document.createdAt()))
                .param("embedding", toVectorLiteral(embedding))
                .update();
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

    static String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length != EMBEDDING_DIMENSION) {
            throw new IllegalArgumentException("Embedding must have " + EMBEDDING_DIMENSION + " dimensions");
        }
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (!Float.isFinite(embedding[index])) {
                throw new IllegalArgumentException("Embedding must contain only finite values");
            }
            if (index > 0) {
                literal.append(',');
            }
            literal.append(embedding[index]);
        }
        return literal.append(']').toString();
    }
}
