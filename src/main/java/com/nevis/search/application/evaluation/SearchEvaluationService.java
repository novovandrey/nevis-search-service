package com.nevis.search.application.evaluation;

import com.nevis.search.application.HybridDocumentSearchMerger;
import com.nevis.search.application.QueryExpander;
import com.nevis.search.application.QueryNormalizer;
import com.nevis.search.application.evaluation.SearchEvaluationOverrides.SearchEvaluationParameters;
import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.EvaluationSemanticSearchPort;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.DocumentSearchResult;
import com.nevis.search.domain.SearchQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Supplier;

@Service
@Profile("evaluation")
public class SearchEvaluationService {

    private final QueryNormalizer queryNormalizer;
    private final QueryExpander queryExpander;
    private final DocumentSearchPort documentSearchPort;
    private final EvaluationSemanticSearchPort semanticSearchPort;
    private final EmbeddingPort embeddingPort;
    private final HybridDocumentSearchMerger hybridDocumentSearchMerger;
    private final SemanticSearchProperties semanticSearchProperties;

    public SearchEvaluationService(
            QueryNormalizer queryNormalizer,
            QueryExpander queryExpander,
            DocumentSearchPort documentSearchPort,
            EvaluationSemanticSearchPort semanticSearchPort,
            EmbeddingPort embeddingPort,
            HybridDocumentSearchMerger hybridDocumentSearchMerger,
            SemanticSearchProperties semanticSearchProperties
    ) {
        this.queryNormalizer = queryNormalizer;
        this.queryExpander = queryExpander;
        this.documentSearchPort = documentSearchPort;
        this.semanticSearchPort = semanticSearchPort;
        this.embeddingPort = embeddingPort;
        this.hybridDocumentSearchMerger = hybridDocumentSearchMerger;
        this.semanticSearchProperties = semanticSearchProperties;
    }

    public SearchEvaluationResult evaluate(
            String rawQuery,
            SearchEvaluationMode mode,
            SemanticRetrievalMode semanticRetrieval,
            SearchEvaluationOverrides overrides
    ) {
        long totalStartedAt = System.nanoTime();
        SearchQuery query = queryNormalizer.normalize(rawQuery);
        SearchEvaluationParameters parameters;
        try {
            parameters = overrides.resolve(semanticSearchProperties);
        } catch (IllegalArgumentException exception) {
            throw new InvalidRequestException(exception.getMessage());
        }

        TimedResult<List<DocumentSearchResult>> lexical = mode == SearchEvaluationMode.SEMANTIC
                ? TimedResult.empty()
                : measure(() -> documentSearchPort.search(
                        queryExpander.expand(query), parameters.documentCandidateLimit()
                ));
        EvaluationSemanticSearchResult semantic = mode == SearchEvaluationMode.LEXICAL
                ? new EvaluationSemanticSearchResult(List.of(), List.of(), 0, 0)
                : semanticSearchPort.search(
                        embeddingPort.embedQuery(query.value()),
                        semanticRetrieval,
                        parameters.documentCandidateLimit(),
                        parameters.chunkCandidateLimit(),
                        parameters.hnswEfSearch(),
                        parameters.minimumSimilarity()
                );
        TimedResult<List<DocumentSearchResult>> fused = mode == SearchEvaluationMode.HYBRID
                ? measure(() -> hybridDocumentSearchMerger.merge(
                        lexical.value(), semantic.documents(), parameters.hybridConfiguration()
                ))
                : TimedResult.empty();

        return new SearchEvaluationResult(
                query.value(),
                mode,
                semanticRetrieval,
                parameters,
                ranked(lexical.value()),
                semantic.chunks(),
                ranked(semantic.documents()),
                ranked(fused.value()),
                diagnostics(semantic.chunks()),
                new SearchEvaluationResult.Timings(
                        lexical.elapsedMs(),
                        semantic.retrievalMs(),
                        semantic.diagnosticsMs(),
                        fused.elapsedMs(),
                        elapsedMs(totalStartedAt)
                )
        );
    }

    public String explain(
            String rawQuery,
            SemanticRetrievalMode semanticRetrieval,
            Integer chunkCandidateLimit,
            Integer hnswEfSearch
    ) {
        SearchQuery query = queryNormalizer.normalize(rawQuery);
        int chunkLimit = chunkCandidateLimit == null
                ? semanticSearchProperties.chunkCandidateLimit() : chunkCandidateLimit;
        int efSearch = hnswEfSearch == null ? semanticSearchProperties.hnswEfSearch() : hnswEfSearch;
        if (chunkLimit < 1 || efSearch < chunkLimit) {
            throw new InvalidRequestException("Invalid semantic plan parameters");
        }
        return semanticSearchPort.explain(
                embeddingPort.embedQuery(query.value()), semanticRetrieval, chunkLimit, efSearch
        );
    }

    private SearchEvaluationResult.ChunkDiagnostics diagnostics(
            List<EvaluationSemanticSearchResult.ChunkHit> chunks
    ) {
        if (chunks.isEmpty()) {
            return new SearchEvaluationResult.ChunkDiagnostics(0, 0, 0);
        }
        Map<java.util.UUID, Long> counts = chunks.stream().collect(Collectors.groupingBy(
                EvaluationSemanticSearchResult.ChunkHit::documentId, Collectors.counting()
        ));
        long maximum = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return new SearchEvaluationResult.ChunkDiagnostics(
                counts.size(), Math.toIntExact(maximum), (double) maximum / chunks.size()
        );
    }

    private List<SearchEvaluationResult.RankedDocument> ranked(List<DocumentSearchResult> results) {
        return java.util.stream.IntStream.range(0, results.size())
                .mapToObj(index -> {
                    DocumentSearchResult result = results.get(index);
                    return new SearchEvaluationResult.RankedDocument(result.document(), index + 1, result.relevance());
                })
                .toList();
    }

    private <T> TimedResult<T> measure(Supplier<T> operation) {
        long startedAt = System.nanoTime();
        T value = operation.get();
        return new TimedResult<>(value, elapsedMs(startedAt));
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record TimedResult<T>(T value, long elapsedMs) {

        private static <T> TimedResult<List<T>> empty() {
            return new TimedResult<>(List.of(), 0);
        }
    }
}
