package com.nevis.search.api.dto;

import com.nevis.search.domain.Document;

import java.time.Instant;
import java.util.UUID;

public record DocumentSearchResponse(
        SearchResultType type,
        UUID id,
        UUID clientId,
        String title,
        Instant createdAt
) implements SearchResultResponse {

    public static DocumentSearchResponse from(Document document) {
        return new DocumentSearchResponse(
                SearchResultType.DOCUMENT,
                document.id(),
                document.clientId(),
                document.title(),
                document.createdAt()
        );
    }
}

