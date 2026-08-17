package com.nevis.search.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nevis.search.domain.Client;

import java.util.UUID;

public record ClientSearchResponse(
        SearchResultType type,
        UUID id,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        String email,
        String countryOfResidence
) implements SearchResultResponse {

    public static ClientSearchResponse from(Client client) {
        return new ClientSearchResponse(
                SearchResultType.CLIENT,
                client.id(),
                client.firstName(),
                client.lastName(),
                client.email(),
                client.countryOfResidence()
        );
    }
}
