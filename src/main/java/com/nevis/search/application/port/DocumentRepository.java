package com.nevis.search.application.port;

import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentChunk;

import java.util.List;

public interface DocumentRepository {

    Document save(Document document, List<DocumentChunk> chunks);
}
