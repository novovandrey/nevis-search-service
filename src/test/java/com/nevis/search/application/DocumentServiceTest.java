package com.nevis.search.application;

import com.nevis.search.application.exception.ClientNotFoundException;
import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.application.port.ClientRepository;
import com.nevis.search.application.port.DocumentRepository;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.config.DocumentProperties;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentServiceTest {

    @Test
    void passesExplicitClientScopeToSearchPort() {
        UUID clientId = UUID.randomUUID();
        AtomicReference<DocumentSearchScope> capturedScope = new AtomicReference<>();
        DocumentService service = service(clientId, (terms, scope, limit) -> {
            capturedScope.set(scope);
            return List.of();
        });

        service.search(clientId, "passport", 10);

        assertThat(capturedScope.get()).isEqualTo(new DocumentSearchScope.Client(clientId));
    }

    @Test
    void rejectsUnknownClientBeforeDocumentSearch() {
        UUID knownClientId = UUID.randomUUID();
        DocumentService service = service(knownClientId, (terms, scope, limit) -> List.of());

        assertThatThrownBy(() -> service.search(UUID.randomUUID(), "passport", 10))
                .isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void enforcesConfiguredDocumentContentLimit() {
        UUID clientId = UUID.randomUUID();
        DocumentService service = service(clientId, (terms, scope, limit) -> List.of());

        assertThatThrownBy(() -> service.create(clientId, "Title", "x".repeat(1_001)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("1000");
    }

    private DocumentService service(UUID knownClientId, DocumentSearchPort searchPort) {
        ClientRepository clients = new ClientRepository() {
            @Override
            public Client save(Client client) {
                return client;
            }

            @Override
            public Optional<Client> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public boolean existsById(UUID id) {
                return knownClientId.equals(id);
            }
        };
        DocumentRepository documents = new DocumentRepository() {
            @Override
            public Document save(Document document) {
                return document;
            }

            @Override
            public Optional<Document> findById(UUID id) {
                return Optional.empty();
            }

            @Override
            public List<Document> findByClientId(UUID clientId, int limit, int offset) {
                return List.of();
            }
        };
        SearchProperties searchProperties = new SearchProperties(200, 20, 100);
        QueryNormalizer normalizer = new QueryNormalizer(searchProperties);
        return new DocumentService(
                clients,
                documents,
                searchPort,
                normalizer,
                new QueryExpander(query -> Set.of()),
                new DocumentProperties(1_000),
                searchProperties
        );
    }
}
