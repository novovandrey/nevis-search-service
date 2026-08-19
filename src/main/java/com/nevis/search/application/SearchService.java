package com.nevis.search.application;

import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.ClientSearchResult;
import com.nevis.search.domain.DocumentSearchResult;
import com.nevis.search.domain.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final QueryNormalizer queryNormalizer;
    private final ClientSearchQueryNormalizer clientSearchQueryNormalizer;
    private final QueryExpander queryExpander;
    private final ClientSearchPort clientSearchPort;
    private final DocumentSearchPort documentSearchPort;
    private final SemanticDocumentSearchPort semanticDocumentSearchPort;
    private final EmbeddingPort embeddingPort;
    private final HybridDocumentSearchMerger hybridDocumentSearchMerger;
    private final SemanticSearchProperties semanticSearchProperties;

    public SearchService(
            QueryNormalizer queryNormalizer,
            ClientSearchQueryNormalizer clientSearchQueryNormalizer,
            QueryExpander queryExpander,
            ClientSearchPort clientSearchPort,
            DocumentSearchPort documentSearchPort,
            SemanticDocumentSearchPort semanticDocumentSearchPort,
            EmbeddingPort embeddingPort,
            HybridDocumentSearchMerger hybridDocumentSearchMerger,
            SemanticSearchProperties semanticSearchProperties
    ) {
        this.queryNormalizer = queryNormalizer;
        this.clientSearchQueryNormalizer = clientSearchQueryNormalizer;
        this.queryExpander = queryExpander;
        this.clientSearchPort = clientSearchPort;
        this.documentSearchPort = documentSearchPort;
        this.semanticDocumentSearchPort = semanticDocumentSearchPort;
        this.embeddingPort = embeddingPort;
        this.hybridDocumentSearchMerger = hybridDocumentSearchMerger;
        this.semanticSearchProperties = semanticSearchProperties;
    }

    public GlobalSearchResults search(String rawQuery) {
        Instant startedAt = Instant.now();
        SearchQuery query = queryNormalizer.normalize(rawQuery);
        List<ClientSearchResult> clients = clientSearchPort.search(
                clientSearchQueryNormalizer.normalize(rawQuery)
        );
        List<DocumentSearchResult> lexicalDocuments = documentSearchPort.search(
                queryExpander.expand(query), semanticSearchProperties.candidateLimit()
        );
        List<DocumentSearchResult> semanticDocuments = semanticSearch(query);
        List<DocumentSearchResult> documents = hybridDocumentSearchMerger.merge(lexicalDocuments, semanticDocuments);
        log.debug("Global search completed in {} ms", Duration.between(startedAt, Instant.now()).toMillis());
        return new GlobalSearchResults(clients, documents);
    }

    private List<DocumentSearchResult> semanticSearch(SearchQuery query) {
        try {
            return semanticDocumentSearchPort.search(
                    embeddingPort.embed(query.value()),
                    semanticSearchProperties.candidateLimit(),
                    semanticSearchProperties.minimumSimilarity()
            );
        } catch (RuntimeException exception) {
            log.warn("Semantic document search failed; returning lexical results only", exception);
            return List.of();
        }
    }

    public record GlobalSearchResults(
            List<ClientSearchResult> clients,
            List<DocumentSearchResult> documents
    ) {
    }
}
