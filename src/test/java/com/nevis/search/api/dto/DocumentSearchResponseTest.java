package com.nevis.search.api.dto;

import com.nevis.search.domain.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSearchResponseTest {

    @Test
    void mapsStoredDocumentContentWithoutChangingIt() {
        Document document = new Document(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Utility Bill",
                "Full document content\nwith original formatting.",
                Instant.parse("2026-08-18T12:00:00Z")
        );

        DocumentSearchResponse response = DocumentSearchResponse.from(document);

        assertThat(response.type()).isEqualTo(SearchResultType.DOCUMENT);
        assertThat(response.content()).isEqualTo(document.content());
    }
}
