package com.nevis.search.application.port;

import com.nevis.search.domain.Client;

import java.util.Optional;
import java.util.UUID;

public interface ClientRepository {

    Client save(Client client);

    Optional<Client> findById(UUID id);

    boolean existsById(UUID id);
}

