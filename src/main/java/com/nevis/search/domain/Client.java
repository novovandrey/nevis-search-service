package com.nevis.search.domain;

import java.util.UUID;

public record Client(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String countryOfResidence
) {
}

