package com.nevis.search.application;

import com.nevis.search.application.exception.ClientNotFoundException;
import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.application.port.ClientRepository;
import com.nevis.search.application.port.DocumentRepository;
import com.nevis.search.config.DocumentProperties;
import com.nevis.search.domain.Document;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class DocumentService {

    private final ClientRepository clientRepository;
    private final DocumentRepository documentRepository;
    private final DocumentProperties documentProperties;

    public DocumentService(
            ClientRepository clientRepository,
            DocumentRepository documentRepository,
            DocumentProperties documentProperties
    ) {
        this.clientRepository = clientRepository;
        this.documentRepository = documentRepository;
        this.documentProperties = documentProperties;
    }

    public Document create(UUID clientId, String title, String content) {
        requireClient(clientId);
        if (content.length() > documentProperties.maxContentLength()) {
            throw new InvalidRequestException(
                    "Document content must not exceed " + documentProperties.maxContentLength() + " characters"
            );
        }
        return documentRepository.save(new Document(
                UUID.randomUUID(), clientId, title.strip(), content.strip(), Instant.now()
        ));
    }

    private void requireClient(UUID clientId) {
        if (!clientRepository.existsById(clientId)) {
            throw new ClientNotFoundException(clientId);
        }
    }

}
