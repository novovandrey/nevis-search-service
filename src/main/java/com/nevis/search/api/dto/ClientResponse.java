package com.nevis.search.api.dto;

import com.nevis.search.domain.Client;

import java.util.UUID;

public record ClientResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String countryOfResidence
) {
    public static ClientResponse from(Client client) {
        return new ClientResponse(
                client.id(),
                client.firstName(),
                client.lastName(),
                client.email(),
                client.countryOfResidence()
        );
    }
}

