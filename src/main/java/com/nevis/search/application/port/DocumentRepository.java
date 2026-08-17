package com.nevis.search.application.port;

import com.nevis.search.domain.Document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    Document save(Document document);

    Optional<Document> findById(UUID id);

    List<Document> findByClientId(UUID clientId, int limit, int offset);
}

