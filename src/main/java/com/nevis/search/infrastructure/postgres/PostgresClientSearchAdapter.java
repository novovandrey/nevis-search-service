package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.config.ClientSearchProperties;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.ClientSearchQuery;
import com.nevis.search.domain.ClientSearchResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PostgresClientSearchAdapter implements ClientSearchPort {

    private final JdbcClient jdbcClient;
    private final ClientSearchProperties properties;

    public PostgresClientSearchAdapter(JdbcClient jdbcClient, ClientSearchProperties properties) {
        this.jdbcClient = jdbcClient;
        this.properties = properties;
    }

    @Override
    public List<ClientSearchResult> search(ClientSearchQuery query) {
        boolean exactOnly = query.value().length() < 3;
        JdbcClient.StatementSpec statement = jdbcClient.sql(exactOnly ? exactSearchSql() : fuzzySearchSql())
                .param("companyKey", query.value());
        if (!exactOnly) {
            statement = statement.param("threshold", properties.trigramThreshold());
        }
        return statement
                .query((resultSet, rowNumber) -> {
                    Client client = PostgresClientRepository.mapClient(resultSet, rowNumber);
                    return new ClientSearchResult(client);
                })
                .list();
    }

    private String exactSearchSql() {
        return """
                SELECT id, first_name, last_name, email, country_of_residence
                FROM clients
                WHERE company_search_key = :companyKey
                ORDER BY last_name, first_name, id
                """;
    }

    private String fuzzySearchSql() {
        return """
                WITH exact_matches AS (
                    SELECT id, first_name, last_name, email, country_of_residence,
                           0 AS match_kind, 1.0::real AS match_similarity
                    FROM clients
                    WHERE company_search_key = :companyKey
                ), fuzzy_candidates AS (
                    SELECT id, first_name, last_name, email, country_of_residence,
                           1 AS match_kind,
                           similarity(company_search_key, :companyKey) AS match_similarity
                    FROM clients
                    WHERE company_search_key % :companyKey
                      AND company_search_key <> :companyKey
                      AND company_search_key NOT LIKE '%' || :companyKey || '%'
                      AND :companyKey NOT LIKE '%' || company_search_key || '%'
                ), matches AS (
                    SELECT * FROM exact_matches
                    UNION ALL
                    SELECT * FROM fuzzy_candidates
                    WHERE match_similarity >= :threshold
                )
                SELECT id, first_name, last_name, email, country_of_residence
                FROM matches
                ORDER BY match_kind, match_similarity DESC, last_name, first_name, id
                """;
    }
}
