package com.nevis.search.application;

import com.nevis.search.application.exception.ClientNotFoundException;
import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.application.port.ClientRepository;
import com.nevis.search.application.port.DocumentRepository;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.config.DocumentProperties;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import com.nevis.search.domain.DocumentSearchScope;
import com.nevis.search.domain.SearchQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private final ClientRepository clientRepository;
    private final DocumentRepository documentRepository;
    private final DocumentSearchPort documentSearchPort;
    private final QueryNormalizer queryNormalizer;
    private final QueryExpander queryExpander;
    private final DocumentProperties documentProperties;
    private final SearchProperties searchProperties;

    public DocumentService(
            ClientRepository clientRepository,
            DocumentRepository documentRepository,
            DocumentSearchPort documentSearchPort,
            QueryNormalizer queryNormalizer,
            QueryExpander queryExpander,
            DocumentProperties documentProperties,
            SearchProperties searchProperties
    ) {
        this.clientRepository = clientRepository;
        this.documentRepository = documentRepository;
        this.documentSearchPort = documentSearchPort;
        this.queryNormalizer = queryNormalizer;
        this.queryExpander = queryExpander;
        this.documentProperties = documentProperties;
        this.searchProperties = searchProperties;
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

    public List<Document> list(UUID clientId, int limit, int offset) {
        requireClient(clientId);
        validatePage(limit, offset);
        return documentRepository.findByClientId(clientId, limit, offset);
    }

    public List<DocumentSearchResult> search(UUID clientId, String rawQuery, int limit) {
        requireClient(clientId);
        validatePage(limit, 0);
        SearchQuery query = queryNormalizer.normalize(rawQuery);
        return documentSearchPort.search(
                queryExpander.expand(query),
                new DocumentSearchScope.Client(clientId),
                limit
        );
    }

    private void requireClient(UUID clientId) {
        if (!clientRepository.existsById(clientId)) {
            throw new ClientNotFoundException(clientId);
        }
    }

    private void validatePage(int limit, int offset) {
        if (limit < 1 || limit > searchProperties.maxLimit()) {
            throw new InvalidRequestException("limit must be between 1 and " + searchProperties.maxLimit());
        }
        if (offset < 0) {
            throw new InvalidRequestException("offset must not be negative");
        }
    }
}

