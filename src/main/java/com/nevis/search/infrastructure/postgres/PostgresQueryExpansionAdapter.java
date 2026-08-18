package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.port.QueryExpansionPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Set;

@Repository
public class PostgresQueryExpansionAdapter implements QueryExpansionPort {

    private final JdbcClient jdbcClient;

    public PostgresQueryExpansionAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Set<String> expand(String normalizedQuery) {
        return new LinkedHashSet<>(jdbcClient.sql("""
                        SELECT related.term
                        FROM search_term_mapping matched
                        JOIN search_term_mapping related ON related.group_key = matched.group_key
                        WHERE matched.term = :query
                        ORDER BY related.term
                        """)
                .param("query", normalizedQuery)
                .query(String.class)
                .list());
    }
}
