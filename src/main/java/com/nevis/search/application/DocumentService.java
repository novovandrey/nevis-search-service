package com.nevis.search.application;

import com.nevis.search.application.exception.ClientNotFoundException;
import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.application.port.ClientRepository;
import com.nevis.search.application.port.DocumentRepository;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.config.DocumentProperties;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentChunk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private final ClientRepository clientRepository;
    private final DocumentRepository documentRepository;
    private final EmbeddingPort embeddingPort;
    private final DocumentProperties documentProperties;
    private final DocumentChunker documentChunker;

    public DocumentService(
            ClientRepository clientRepository,
            DocumentRepository documentRepository,
            EmbeddingPort embeddingPort,
            DocumentProperties documentProperties,
            DocumentChunker documentChunker
    ) {
        this.clientRepository = clientRepository;
        this.documentRepository = documentRepository;
        this.embeddingPort = embeddingPort;
        this.documentProperties = documentProperties;
        this.documentChunker = documentChunker;
    }

    @Transactional
    public Document create(UUID clientId, String title, String content) {
        requireClient(clientId);
        if (content.length() > documentProperties.maxContentLength()) {
            throw new InvalidRequestException(
                    "Document content must not exceed " + documentProperties.maxContentLength() + " characters"
            );
        }
        Document document = new Document(UUID.randomUUID(), clientId, title.strip(), content, Instant.now());
        List<DocumentChunk> chunks = documentChunker.chunk(document.title(), document.content()).stream()
                .map(chunk -> new DocumentChunk(
                        chunk.index(), chunk.body(), embeddingPort.embedPassage(chunk.embeddingInput())
                ))
                .toList();
        return documentRepository.save(document, chunks);
    }

    private void requireClient(UUID clientId) {
        if (!clientRepository.existsById(clientId)) {
            throw new ClientNotFoundException(clientId);
        }
    }

}
