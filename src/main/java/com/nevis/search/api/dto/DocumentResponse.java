package com.nevis.search.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nevis.search.domain.Document;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        UUID clientId,
        String title,
        String content,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double relevance
) {
    public static DocumentResponse from(Document document) {
        return from(document, null);
    }

    public static DocumentResponse from(Document document, Double relevance) {
        return new DocumentResponse(
                document.id(),
                document.clientId(),
                document.title(),
                document.content(),
                document.createdAt(),
                relevance
        );
    }
}

