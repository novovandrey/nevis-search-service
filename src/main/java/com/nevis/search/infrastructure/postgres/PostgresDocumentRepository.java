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

    private final JdbcClient jdbcClient;

    public PostgresDocumentRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Document save(Document document) {
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
}
