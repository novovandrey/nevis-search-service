package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.ClientSearchQuery;
import com.nevis.search.domain.ClientSearchResult;
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
    public List<ClientSearchResult> search(ClientSearchQuery query) {
        return jdbcClient.sql("""
                        WITH candidates AS (
                            SELECT c.*,
                                   CASE
                                       WHEN lower(c.email) ~
                                            '^[^@[:space:]]+@[^@.[:space:]]+(\\.[^@.[:space:]]+)+$'
                                       THEN regexp_replace(
                                           split_part(lower(c.email), '@', 2),
                                           '\\.[^.]+$',
                                           ''
                                       )
                                       ELSE NULL
                                   END AS company_key
                            FROM clients c
                        )
                        SELECT id, first_name, last_name, email, country_of_residence
                        FROM candidates
                        WHERE company_key = :companyKey
                        ORDER BY last_name, first_name, id
                        """)
                .param("companyKey", query.value())
                .query((resultSet, rowNumber) -> {
                    Client client = PostgresClientRepository.mapClient(resultSet, rowNumber);
                    return new ClientSearchResult(client);
                })
                .list();
    }
}
