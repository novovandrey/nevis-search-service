package com.nevis.search.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nevis.search.domain.Document;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record DocumentSearchResponse(
        SearchResultType type,
        UUID id,
        @JsonProperty("client_id") UUID clientId,
        String title,
        @Schema(description = "Stored document content; populated for DOCUMENT search results only")
        String content,
        @JsonProperty("created_at") Instant createdAt
) implements SearchResultResponse {

    public static DocumentSearchResponse from(Document document) {
        return new DocumentSearchResponse(
                SearchResultType.DOCUMENT,
                document.id(),
                document.clientId(),
                document.title(),
                document.content(),
                document.createdAt()
        );
    }
}
