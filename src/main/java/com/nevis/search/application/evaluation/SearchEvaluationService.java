package com.nevis.search.application.evaluation;

import com.nevis.search.application.HybridDocumentSearchMerger;
import com.nevis.search.application.QueryExpander;
import com.nevis.search.application.QueryNormalizer;
import com.nevis.search.application.evaluation.SearchEvaluationOverrides.SearchEvaluationParameters;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.DocumentSearchResult;
import com.nevis.search.domain.SearchQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

@Service
@Profile("evaluation")
public class SearchEvaluationService {

    private final QueryNormalizer queryNormalizer;
    private final QueryExpander queryExpander;
    private final DocumentSearchPort documentSearchPort;
    private final SemanticDocumentSearchPort semanticDocumentSearchPort;
    private final EmbeddingPort embeddingPort;
    private final HybridDocumentSearchMerger hybridDocumentSearchMerger;
    private final SemanticSearchProperties semanticSearchProperties;

    public SearchEvaluationService(
            QueryNormalizer queryNormalizer,
            QueryExpander queryExpander,
            DocumentSearchPort documentSearchPort,
            SemanticDocumentSearchPort semanticDocumentSearchPort,
            EmbeddingPort embeddingPort,
            HybridDocumentSearchMerger hybridDocumentSearchMerger,
            SemanticSearchProperties semanticSearchProperties
    ) {
        this.queryNormalizer = queryNormalizer;
        this.queryExpander = queryExpander;
        this.documentSearchPort = documentSearchPort;
        this.semanticDocumentSearchPort = semanticDocumentSearchPort;
        this.embeddingPort = embeddingPort;
        this.hybridDocumentSearchMerger = hybridDocumentSearchMerger;
        this.semanticSearchProperties = semanticSearchProperties;
    }

    public SearchEvaluationResult evaluate(
            String rawQuery,
            SearchEvaluationMode mode,
            SearchEvaluationOverrides overrides
    ) {
        long totalStartedAt = System.nanoTime();
        SearchQuery query = queryNormalizer.normalize(rawQuery);
        SearchEvaluationParameters parameters = overrides.resolve(semanticSearchProperties);

        TimedResult<List<DocumentSearchResult>> lexical = mode == SearchEvaluationMode.SEMANTIC
                ? TimedResult.empty()
                : measure(() -> documentSearchPort.search(
                        queryExpander.expand(query), parameters.candidateLimit()
                ));
        TimedResult<List<DocumentSearchResult>> semantic = mode == SearchEvaluationMode.LEXICAL
                ? TimedResult.empty()
                : measure(() -> semanticDocumentSearchPort.search(
                        embeddingPort.embed(query.value()),
                        parameters.candidateLimit(),
                        parameters.minimumSimilarity()
                ));
        TimedResult<List<DocumentSearchResult>> fused = mode == SearchEvaluationMode.HYBRID
                ? measure(() -> hybridDocumentSearchMerger.merge(
                        lexical.value(), semantic.value(), parameters.hybridConfiguration()
                ))
                : TimedResult.empty();

        return new SearchEvaluationResult(
                query.value(),
                mode,
                parameters,
                ranked(lexical.value()),
                ranked(semantic.value()),
                ranked(fused.value()),
                new SearchEvaluationResult.Timings(
                        lexical.elapsedMs(),
                        semantic.elapsedMs(),
                        fused.elapsedMs(),
                        elapsedMs(totalStartedAt)
                )
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
