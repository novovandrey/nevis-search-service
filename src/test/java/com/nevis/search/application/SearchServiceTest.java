package com.nevis.search.application;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.embedding.EmbeddingVector;
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
        SemanticSearchProperties semanticProperties = new SemanticSearchProperties(
                50, 250, 500, 60, 0.30, 1.25, 1.0
        );
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
        SemanticDocumentSearchPort semanticSearch = (
                embedding, documentLimit, chunkLimit, efSearch, minimumSimilarity
        ) -> List.of();
        EmbeddingModelCapabilities capabilities = new EmbeddingModelCapabilities("test", 384, 510);
        AtomicReference<String> embeddedQuery = new AtomicReference<>();
        EmbeddingPort embeddings = queryEmbeddings(capabilities, embeddedQuery);
        SearchService service = new SearchService(
                normalizer, clientNormalizer, expander, clientSearch, documentSearch,
                semanticSearch, embeddings, new HybridDocumentSearchMerger(semanticProperties, properties), semanticProperties
        );

        SearchService.GlobalSearchResults result = service.search("Address Proof");

        assertThat(result.clients()).extracting(ClientSearchResult::client).containsExactly(client);
        assertThat(result.documents()).extracting(DocumentSearchResult::document).containsExactly(document);
        assertThat(capturedClientQuery.get().value()).isEqualTo("addressproof");
        assertThat(capturedTerms.get()).containsExactlyInAnyOrder("address proof", "utility bill");
        assertThat(embeddedQuery).hasValue("address proof");
    }

    @Test
    void returnsLexicalDocumentsWhenSemanticSearchFails() {
        SearchProperties properties = new SearchProperties(255, 50);
        SemanticSearchProperties semanticProperties = new SemanticSearchProperties(
                50, 250, 500, 60, 0.30, 1.25, 1.0
        );
        QueryNormalizer normalizer = new QueryNormalizer(properties);
        Document document = new Document(
                UUID.randomUUID(), UUID.randomUUID(), "Passport", "Official identity record", Instant.now()
        );
        EmbeddingModelCapabilities capabilities = new EmbeddingModelCapabilities("test", 384, 510);
        SearchService service = new SearchService(
                normalizer,
                new ClientSearchQueryNormalizer(properties),
                new QueryExpander(query -> Set.of()),
                query -> List.of(),
                (terms, limit) -> List.of(new DocumentSearchResult(document, 1.0)),
                (embedding, documentLimit, chunkLimit, efSearch, minimumSimilarity) -> {
                    throw new IllegalStateException("Vector search unavailable");
                },
                queryEmbeddings(capabilities, new AtomicReference<>()),
                new HybridDocumentSearchMerger(semanticProperties, properties),
                semanticProperties
        );

        assertThat(service.search("passport").documents())
                .extracting(DocumentSearchResult::document)
                .containsExactly(document);
    }

    private EmbeddingPort queryEmbeddings(
            EmbeddingModelCapabilities capabilities,
            AtomicReference<String> embeddedQuery
    ) {
        return new EmbeddingPort() {
            @Override
            public EmbeddingVector embedQuery(String text) {
                embeddedQuery.set(text);
                return EmbeddingVector.of(new float[384], capabilities);
            }

            @Override
            public EmbeddingVector embedPassage(String text) {
                throw new AssertionError("SearchService must not embed passages");
            }
        };
    }
}
