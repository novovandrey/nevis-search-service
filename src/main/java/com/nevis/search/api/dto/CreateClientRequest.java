package com.nevis.search.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClientRequest(
        @JsonProperty("first_name") @JsonAlias("firstName")
        @NotBlank @Size(max = 100) String firstName,
        @JsonProperty("last_name") @JsonAlias("lastName")
        @NotBlank @Size(max = 100) String lastName,
        @NotBlank @Email @Size(max = 254) String email,
        @Size(max = 100) String countryOfResidence
) {
}
