package com.nevis.search.domain;

import java.util.Objects;

public record ClientSearchQuery(String value) {

    public ClientSearchQuery {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
