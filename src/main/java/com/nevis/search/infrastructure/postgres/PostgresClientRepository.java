package com.nevis.search.infrastructure.postgres;

import com.nevis.search.application.port.ClientRepository;
import com.nevis.search.domain.Client;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresClientRepository implements ClientRepository {

    private final JdbcClient jdbcClient;

    public PostgresClientRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Client save(Client client) {
        jdbcClient.sql("""
                        INSERT INTO clients (id, first_name, last_name, email, country_of_residence)
                        VALUES (:id, :firstName, :lastName, :email, :countryOfResidence)
                        """)
                .param("id", client.id())
                .param("firstName", client.firstName())
                .param("lastName", client.lastName())
                .param("email", client.email())
                .param("countryOfResidence", client.countryOfResidence())
                .update();
        return client;
    }

    @Override
    public Optional<Client> findById(UUID id) {
        return jdbcClient.sql("""
                        SELECT id, first_name, last_name, email, country_of_residence
                        FROM clients
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(PostgresClientRepository::mapClient)
                .optional();
    }

    @Override
    public boolean existsById(UUID id) {
        return Boolean.TRUE.equals(jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM clients WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single());
    }

    static Client mapClient(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Client(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("first_name"),
                resultSet.getString("last_name"),
                resultSet.getString("email"),
                resultSet.getString("country_of_residence")
        );
    }
}

