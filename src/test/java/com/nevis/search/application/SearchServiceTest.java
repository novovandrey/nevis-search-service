package com.nevis.search.application;

import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.ClientSearchResult;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import com.nevis.search.domain.DocumentSearchScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchServiceTest {

    @Test
    void orchestratesClientAndAllClientsDocumentSearchWithoutMergingScores() {
        SearchProperties properties = new SearchProperties(200, 20, 100);
        QueryNormalizer normalizer = new QueryNormalizer(properties);
        QueryExpander expander = new QueryExpander(query -> Set.of("utility bill"));
        Client client = new Client(UUID.randomUUID(), "Anton", "Batiaev", "a@neviswealth.com", null);
        Document document = new Document(
                UUID.randomUUID(), client.id(), "Utility Bill", "Address", Instant.now()
        );
        ClientSearchPort clientSearch = (query, limit) -> List.of(new ClientSearchResult(client, 2));
        AtomicReference<DocumentSearchScope> capturedScope = new AtomicReference<>();
        AtomicReference<Set<String>> capturedTerms = new AtomicReference<>();
        DocumentSearchPort documentSearch = (terms, scope, limit) -> {
            capturedScope.set(scope);
            capturedTerms.set(terms);
            return List.of(new DocumentSearchResult(document, 0.4));
        };
        SearchService service = new SearchService(
                normalizer, expander, clientSearch, documentSearch, properties
        );

        SearchService.GlobalSearchResults result = service.search("Address Proof", 20);

        assertThat(result.clients()).extracting(ClientSearchResult::client).containsExactly(client);
        assertThat(result.documents()).extracting(DocumentSearchResult::document).containsExactly(document);
        assertThat(capturedScope.get()).isInstanceOf(DocumentSearchScope.AllClients.class);
        assertThat(capturedTerms.get()).containsExactlyInAnyOrder("address proof", "utility bill");
    }
}

