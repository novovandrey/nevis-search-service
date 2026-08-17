package com.nevis.search.domain;

import java.time.Instant;
import java.util.UUID;

public record Document(
        UUID id,
        UUID clientId,
        String title,
        String content,
        Instant createdAt
) {
}

