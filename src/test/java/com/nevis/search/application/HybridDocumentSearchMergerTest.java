package com.nevis.search.application;

import com.nevis.search.config.SearchProperties;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HybridDocumentSearchMergerTest {

    private final HybridDocumentSearchMerger merger = new HybridDocumentSearchMerger(
            new SemanticSearchProperties(50, 60, 0.30), new SearchProperties(255, 50)
    );

    @Test
    void combinesRankedListsDeduplicatesAndUsesDeterministicTieBreakers() {
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        Document lexicalOnly = document("00000000-0000-0000-0000-000000000001", now);
        Document inBoth = document("00000000-0000-0000-0000-000000000002", now.minusSeconds(1));
        Document semanticOnly = document("00000000-0000-0000-0000-000000000003", now.plusSeconds(1));

        List<DocumentSearchResult> merged = merger.merge(
                List.of(new DocumentSearchResult(lexicalOnly, 0.9), new DocumentSearchResult(inBoth, 0.8)),
                List.of(
                        new DocumentSearchResult(semanticOnly, 0.9),
                        new DocumentSearchResult(inBoth, 0.8)
                )
        );

        assertThat(merged).extracting(result -> result.document().id())
                .containsExactly(inBoth.id(), semanticOnly.id(), lexicalOnly.id());
        assertThat(merged).hasSize(3);
    }

    private Document document(String id, Instant createdAt) {
        return new Document(UUID.fromString(id), UUID.randomUUID(), "title", "content", createdAt);
    }
}
