package com.nevis.search.domain;

import java.util.UUID;

public sealed interface DocumentSearchScope {

    record AllClients() implements DocumentSearchScope {
    }

    record Client(UUID clientId) implements DocumentSearchScope {
        public Client {
            if (clientId == null) {
                throw new IllegalArgumentException("clientId is required");
            }
        }
    }
}

