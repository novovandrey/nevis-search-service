package com.nevis.search.application;

import com.nevis.search.application.exception.ClientNotFoundException;
import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.application.port.ClientRepository;
import com.nevis.search.application.port.DocumentRepository;
import com.nevis.search.config.DocumentProperties;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.Document;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentServiceTest {

    @Test
    void rejectsUnknownClientBeforeDocumentCreation() {
        UUID clientId = UUID.randomUUID();
        DocumentService service = service(clientId);

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), "Title", "Content"))
                .isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void enforcesConfiguredDocumentContentLimit() {
        UUID clientId = UUID.randomUUID();
        DocumentService service = service(clientId);

        assertThatThrownBy(() -> service.create(clientId, "Title", "x".repeat(1_001)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("1000");
    }

    @Test
    void doesNotPersistDocumentWhenEmbeddingGenerationFails() {
        UUID clientId = UUID.randomUUID();
        AtomicBoolean saved = new AtomicBoolean();
        ClientRepository clients = clientRepository(clientId);
        DocumentRepository documents = (document, embedding) -> {
            saved.set(true);
            return document;
        };
        DocumentService service = new DocumentService(
                clients, documents, text -> {
                    throw new IllegalStateException("Embedding unavailable");
                }, new DocumentProperties(1_000)
        );

        assertThatThrownBy(() -> service.create(clientId, "Title", "Content"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Embedding unavailable");
        assertThat(saved).isFalse();
    }

    private DocumentService service(UUID knownClientId) {
        ClientRepository clients = clientRepository(knownClientId);
        DocumentRepository documents = new DocumentRepository() {
            @Override
            public Document save(Document document, float[] embedding) {
                return document;
            }
        };
        return new DocumentService(
                clients,
                documents,
                text -> new float[384],
                new DocumentProperties(1_000)
        );
    }

    private ClientRepository clientRepository(UUID knownClientId) {
        return new ClientRepository() {
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
    }
}
