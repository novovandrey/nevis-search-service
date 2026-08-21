package com.nevis.search.application;

import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.application.observability.SearchMetrics;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.ClientSearchQuery;
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
    private final SearchMetrics metrics;

    public SearchService(
            QueryNormalizer queryNormalizer,
            ClientSearchQueryNormalizer clientSearchQueryNormalizer,
            QueryExpander queryExpander,
            ClientSearchPort clientSearchPort,
            DocumentSearchPort documentSearchPort,
            SemanticDocumentSearchPort semanticDocumentSearchPort,
            EmbeddingPort embeddingPort,
            HybridDocumentSearchMerger hybridDocumentSearchMerger,
            SemanticSearchProperties semanticSearchProperties,
            SearchMetrics metrics
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
        this.metrics = metrics;
    }

    public GlobalSearchResults search(String rawQuery) {
        SearchQuery query = queryNormalizer.normalize(rawQuery);
        ClientSearchQuery clientQuery = clientSearchQueryNormalizer.normalize(rawQuery);
        return metrics.recordSearch(() -> executeSearch(query, clientQuery));
    }

    private GlobalSearchResults executeSearch(SearchQuery query, ClientSearchQuery clientQuery) {
        Instant startedAt = Instant.now();
        List<ClientSearchResult> clients = clientSearchPort.search(clientQuery);
        metrics.recordClientMatches(clients);
        List<DocumentSearchResult> lexicalDocuments = metrics.recordFts(() -> documentSearchPort.search(
                queryExpander.expand(query), semanticSearchProperties.candidateLimit()
        ));
        metrics.recordLexicalCandidates(lexicalDocuments.size());
        List<DocumentSearchResult> semanticDocuments = semanticSearch(query);
        metrics.recordSemanticCandidates(semanticDocuments.size());
        List<DocumentSearchResult> documents = hybridDocumentSearchMerger.merge(lexicalDocuments, semanticDocuments);
        metrics.recordResults(clients.size() + documents.size());
        log.debug("Global search completed in {} ms", Duration.between(startedAt, Instant.now()).toMillis());
        return new GlobalSearchResults(clients, documents);
    }

    private List<DocumentSearchResult> semanticSearch(SearchQuery query) {
        try {
            return metrics.recordSemantic(() -> semanticDocumentSearchPort.search(
                    metrics.recordQueryEmbedding(() -> embeddingPort.embedQuery(query.value())),
                    semanticSearchProperties.candidateLimit(),
                    semanticSearchProperties.chunkCandidateLimit(),
                    semanticSearchProperties.hnswEfSearch(),
                    semanticSearchProperties.minimumSimilarity()
            ));
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
