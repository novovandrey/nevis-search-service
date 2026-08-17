package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.ClientSearchResult;
import com.nevis.search.domain.SearchQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PostgresClientSearchAdapter implements ClientSearchPort {

    private final JdbcClient jdbcClient;

    public PostgresClientSearchAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<ClientSearchResult> search(SearchQuery query, int limit) {
        return jdbcClient.sql("""
                        WITH input AS (
                            SELECT lower(:query) AS query,
                                   regexp_replace(lower(:query), '[^a-z0-9]', '', 'g') AS compact_query
                        ), candidates AS (
                            SELECT c.*,
                                   split_part(lower(c.email), '@', 2) AS email_domain,
                                   regexp_replace(split_part(lower(c.email), '@', 2), '[^a-z0-9]', '', 'g') AS compact_domain,
                                   input.query,
                                   input.compact_query
                            FROM clients c
                            CROSS JOIN input
                        )
                        SELECT id, first_name, last_name, email, country_of_residence,
                               CASE
                                   WHEN lower(email) = query THEN 1
                                   WHEN compact_domain = compact_query OR compact_domain LIKE compact_query || '%' THEN 2
                                   WHEN lower(first_name) LIKE query || '%'
                                        OR lower(last_name) LIKE query || '%'
                                        OR lower(first_name || ' ' || last_name) LIKE query || '%' THEN 3
                                   ELSE 4
                               END AS relevance_order
                        FROM candidates
                        WHERE lower(email) = query
                           OR compact_domain LIKE '%' || compact_query || '%'
                           OR lower(first_name) LIKE '%' || query || '%'
                           OR lower(last_name) LIKE '%' || query || '%'
                           OR lower(first_name || ' ' || last_name) LIKE '%' || query || '%'
                        ORDER BY relevance_order, last_name, first_name, id
                        LIMIT :limit
                        """)
                .param("query", query.value())
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    Client client = PostgresClientRepository.mapClient(resultSet, rowNumber);
                    return new ClientSearchResult(client, resultSet.getInt("relevance_order"));
                })
                .list();
    }
}

