package com.nevis.search.application;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.application.exception.ClientNotFoundException;
import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.application.observability.DocumentMetrics;
import com.nevis.search.application.port.ClientRepository;
import com.nevis.search.application.port.DocumentRepository;
import com.nevis.search.application.port.EmbeddingTokenizerPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.config.DocumentChunkingProperties;
import com.nevis.search.config.DocumentProperties;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentServiceTest {

    @Test
    void rejectsUnknownClientBeforeDocumentCreation() {
        UUID clientId = UUID.randomUUID();
        DocumentService service = service(clientId, 1_000);

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), "Title", "Content"))
                .isInstanceOf(ClientNotFoundException.class);
    }

    @Test
    void acceptsExactContentLimitAndRejectsTheNextCharacter() {
        UUID clientId = UUID.randomUUID();
        DocumentService service = service(clientId, 50_000);

        assertThat(service.create(clientId, "Title", "x".repeat(50_000)).content()).hasSize(50_000);
        assertThatThrownBy(() -> service.create(clientId, "Title", "x".repeat(50_001)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("50000");
    }

    @Test
    void doesNotPersistDocumentWhenEmbeddingGenerationFails() {
        UUID clientId = UUID.randomUUID();
        AtomicBoolean saved = new AtomicBoolean();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ClientRepository clients = clientRepository(clientId);
        DocumentRepository documents = (document, chunks) -> {
            saved.set(true);
            return document;
        };
        DocumentService service = new DocumentService(
                clients, documents, passageEmbeddings(text -> {
                    throw new IllegalStateException("Embedding unavailable");
                }), new DocumentProperties(1_000), chunker(),
                new DocumentMetrics(registry, new DocumentProperties(1_000))
        );

        assertThatThrownBy(() -> service.create(clientId, "Title", "Content"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Embedding unavailable");
        assertThat(saved).isFalse();
        assertThat(registry.get("document.create.requests").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("document.create.latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("document.embedding.latency").timer().count()).isEqualTo(1L);
    }

    @Test
    void recordsUtf8SizeChunkCountAndOneEmbeddingObservationPerDocument() {
        UUID clientId = UUID.randomUUID();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DocumentService service = new DocumentService(
                clientRepository(clientId),
                (document, chunks) -> document,
                passageEmbeddings(text -> EmbeddingVector.of(new float[384], capabilities())),
                new DocumentProperties(1_000),
                chunker(),
                new DocumentMetrics(registry, new DocumentProperties(1_000))
        );

        service.create(clientId, "Title", "€x");

        assertThat(registry.get("document.create.requests").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("document.create.latency").timer().count()).isEqualTo(1L);
        assertThat(registry.get("document.size.bytes").summary().totalAmount()).isEqualTo(4.0);
        assertThat(registry.get("document.chunks.created").summary().totalAmount()).isEqualTo(1.0);
        assertThat(registry.get("document.embedding.latency").timer().count()).isEqualTo(1L);
    }

    private DocumentService service(UUID knownClientId, int contentLimit) {
        ClientRepository clients = clientRepository(knownClientId);
        DocumentRepository documents = new DocumentRepository() {
            @Override
            public Document save(Document document, List<DocumentChunk> chunks) {
                assertThat(chunks).isNotEmpty();
                return document;
            }
        };
        return new DocumentService(
                clients,
                documents,
                passageEmbeddings(text -> EmbeddingVector.of(new float[384], capabilities())),
                new DocumentProperties(contentLimit),
                chunker(),
                new DocumentMetrics(new SimpleMeterRegistry(), new DocumentProperties(contentLimit))
        );
    }

    private EmbeddingPort passageEmbeddings(Function<String, EmbeddingVector> embeddings) {
        return new EmbeddingPort() {
            @Override
            public EmbeddingVector embedQuery(String text) {
                throw new AssertionError("DocumentService must not embed queries");
            }

            @Override
            public EmbeddingVector embedPassage(String text) {
                return embeddings.apply(text);
            }
        };
    }

    private DocumentChunker chunker() {
        return new DocumentChunker(
                new WhitespaceTokenizer(),
                new DocumentChunkingProperties(240, 32, 30),
                capabilities()
        );
    }

    private EmbeddingModelCapabilities capabilities() {
        return new EmbeddingModelCapabilities("test", 384, 510);
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

    private static final class WhitespaceTokenizer implements EmbeddingTokenizerPort {

        @Override
        public int countTokens(String text) {
            return tokens(text).size();
        }

        @Override
        public String slice(String text, int fromTokenInclusive, int toTokenExclusive) {
            return String.join(" ", tokens(text).subList(fromTokenInclusive, toTokenExclusive));
        }

        private List<String> tokens(String text) {
            return text.isBlank() ? List.of() : Arrays.asList(text.strip().split("\\s+"));
        }
    }
}
