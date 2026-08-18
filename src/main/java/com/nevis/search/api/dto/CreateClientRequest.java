package com.nevis.search.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClientRequest(
        @JsonProperty("first_name") @JsonAlias("firstName")
        @NotBlank @Size(min = 1, max = 100) @Schema(minLength = 1, maxLength = 100) String firstName,
        @JsonProperty("last_name") @JsonAlias("lastName")
        @NotBlank @Size(min = 1, max = 100) @Schema(minLength = 1, maxLength = 100) String lastName,
        @NotBlank @Email @Size(min = 1, max = 254) @Schema(minLength = 1, maxLength = 254) String email,
        @Size(max = 100) String countryOfResidence
) {
}
