package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import com.nevis.search.domain.DocumentSearchScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
public class PostgresDocumentSearchAdapter implements DocumentSearchPort {

    private final JdbcClient jdbcClient;

    public PostgresDocumentSearchAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<DocumentSearchResult> search(Set<String> terms, DocumentSearchScope scope, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }

        List<String> orderedTerms = new ArrayList<>(terms);
        String placeholders = java.util.stream.IntStream.range(0, orderedTerms.size())
                .mapToObj(index -> "(:term" + index + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        String clientPredicate = scope instanceof DocumentSearchScope.Client
                ? "AND d.client_id = :clientId"
                : "";

        String sql = """
                WITH search_terms(term) AS (VALUES %s),
                queries AS (
                    SELECT websearch_to_tsquery('english', term) AS query
                    FROM search_terms
                )
                SELECT d.id, d.client_id, d.title, d.content, d.created_at,
                       max(ts_rank_cd(d.search_vector, queries.query)) AS relevance
                FROM documents d
                CROSS JOIN queries
                WHERE d.search_vector @@ queries.query
                  %s
                GROUP BY d.id, d.client_id, d.title, d.content, d.created_at
                ORDER BY relevance DESC, d.created_at DESC, d.id
                LIMIT :limit
                """.formatted(placeholders, clientPredicate);

        JdbcClient.StatementSpec statement = jdbcClient.sql(sql).param("limit", limit);
        for (int index = 0; index < orderedTerms.size(); index++) {
            statement = statement.param("term" + index, orderedTerms.get(index));
        }
        if (scope instanceof DocumentSearchScope.Client clientScope) {
            statement = statement.param("clientId", clientScope.clientId());
        }

        return statement.query((resultSet, rowNumber) -> {
            Document document = PostgresDocumentRepository.mapDocument(resultSet, rowNumber);
            return new DocumentSearchResult(document, resultSet.getDouble("relevance"));
        }).list();
    }
}
