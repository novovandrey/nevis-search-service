package com.nevis.search.application;

import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.ClientSearchQuery;
import com.nevis.search.domain.ClientSearchResult;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    @Test
    void orchestratesLexicalAndSemanticDocumentSearch() {
        SearchProperties properties = new SearchProperties(255, 50);
        SemanticSearchProperties semanticProperties = new SemanticSearchProperties(50, 60, 0.30);
        QueryNormalizer normalizer = new QueryNormalizer(properties);
        ClientSearchQueryNormalizer clientNormalizer = new ClientSearchQueryNormalizer(properties);
        QueryExpander expander = new QueryExpander(query -> Set.of("utility bill"));
        Client client = new Client(UUID.randomUUID(), "Anton", "Batiaev", "a@neviswealth.com", null);
        Document document = new Document(
                UUID.randomUUID(), client.id(), "Utility Bill", "Address", Instant.now()
        );
        AtomicReference<ClientSearchQuery> capturedClientQuery = new AtomicReference<>();
        ClientSearchPort clientSearch = query -> {
            capturedClientQuery.set(query);
            return List.of(new ClientSearchResult(client));
        };
        AtomicReference<Set<String>> capturedTerms = new AtomicReference<>();
        DocumentSearchPort documentSearch = (terms, limit) -> {
            capturedTerms.set(terms);
            return List.of(new DocumentSearchResult(document, 0.4));
        };
        SemanticDocumentSearchPort semanticSearch = (embedding, limit, minimumSimilarity) -> List.of();
        EmbeddingPort embeddings = text -> new float[384];
        SearchService service = new SearchService(
                normalizer, clientNormalizer, expander, clientSearch, documentSearch,
                semanticSearch, embeddings, new HybridDocumentSearchMerger(semanticProperties, properties), semanticProperties
        );

        SearchService.GlobalSearchResults result = service.search("Address Proof");

        assertThat(result.clients()).extracting(ClientSearchResult::client).containsExactly(client);
        assertThat(result.documents()).extracting(DocumentSearchResult::document).containsExactly(document);
        assertThat(capturedClientQuery.get().value()).isEqualTo("addressproof");
        assertThat(capturedTerms.get()).containsExactlyInAnyOrder("address proof", "utility bill");
    }
}
