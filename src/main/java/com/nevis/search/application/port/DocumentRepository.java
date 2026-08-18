package com.nevis.search.application.port;

import com.nevis.search.domain.Document;

public interface DocumentRepository {

    Document save(Document document);
}
