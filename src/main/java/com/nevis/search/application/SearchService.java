package com.nevis.search.application;

import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.config.SearchProperties;
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
    private final SearchProperties searchProperties;

    public SearchService(
            QueryNormalizer queryNormalizer,
            ClientSearchQueryNormalizer clientSearchQueryNormalizer,
            QueryExpander queryExpander,
            ClientSearchPort clientSearchPort,
            DocumentSearchPort documentSearchPort,
            SearchProperties searchProperties
    ) {
        this.queryNormalizer = queryNormalizer;
        this.clientSearchQueryNormalizer = clientSearchQueryNormalizer;
        this.queryExpander = queryExpander;
        this.clientSearchPort = clientSearchPort;
        this.documentSearchPort = documentSearchPort;
        this.searchProperties = searchProperties;
    }

    public GlobalSearchResults search(String rawQuery, int limit) {
        if (limit < 1 || limit > searchProperties.maxLimit()) {
            throw new InvalidRequestException("limit must be between 1 and " + searchProperties.maxLimit());
        }

        Instant startedAt = Instant.now();
        SearchQuery query = queryNormalizer.normalize(rawQuery);
        List<ClientSearchResult> clients = clientSearchPort.search(clientSearchQueryNormalizer.normalize(rawQuery))
                .stream()
                .limit(limit)
                .toList();
        List<DocumentSearchResult> documents = documentSearchPort.search(queryExpander.expand(query), limit);
        log.debug("Global search completed in {} ms", Duration.between(startedAt, Instant.now()).toMillis());
        return new GlobalSearchResults(clients, documents);
    }

    public record GlobalSearchResults(
            List<ClientSearchResult> clients,
            List<DocumentSearchResult> documents
    ) {
    }
}
