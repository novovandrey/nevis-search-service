package com.nevis.search.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nevis.search.domain.Document;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        @JsonProperty("client_id") UUID clientId,
        String title,
        String content,
        @JsonProperty("created_at") Instant createdAt
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.id(),
                document.clientId(),
                document.title(),
                document.content(),
                document.createdAt()
        );
    }
}
