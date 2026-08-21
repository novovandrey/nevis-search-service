package com.nevis.search.application.observability;

import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.ClientSearchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
public class SearchMetrics {

    private final Counter requests;
    private final Counter zeroResults;
    private final Counter exactClientMatches;
    private final Counter fuzzyClientMatches;
    private final Counter noClientMatches;
    private final Timer latency;
    private final Timer ftsLatency;
    private final Timer semanticLatency;
    private final Timer embeddingLatency;
    private final DistributionSummary results;
    private final DistributionSummary lexicalCandidates;
    private final DistributionSummary semanticCandidates;

    public SearchMetrics(MeterRegistry registry, SemanticSearchProperties searchProperties) {
        requests = counter(registry, "search.requests", "Accepted global search executions");
        zeroResults = counter(registry, "search.zero.results", "Global searches with no client or document results");
        exactClientMatches = counter(registry, "client.search.exact.match", "Searches containing an exact client company match");
        fuzzyClientMatches = counter(registry, "client.search.fuzzy.match", "Searches containing a fuzzy client company match");
        noClientMatches = counter(registry, "client.search.no.match", "Searches containing no client company match");
        latency = timer(registry, "search.latency", "Complete global search latency");
        ftsLatency = timer(registry, "search.fts.latency", "PostgreSQL full-text retrieval latency");
        semanticLatency = timer(registry, "search.semantic.latency", "Complete semantic retrieval latency");
        embeddingLatency = timer(registry, "search.embedding.latency", "Query embedding generation latency");
        results = summary(registry, "search.results", "Final client and document results returned per search");
        lexicalCandidates = summary(
                registry,
                "search.lexical.candidates",
                "Lexical document candidates entering rank fusion",
                searchProperties.candidateLimit()
        );
        semanticCandidates = summary(
                registry,
                "search.semantic.candidates",
                "Collapsed semantic document candidates entering rank fusion",
                searchProperties.candidateLimit()
        );
    }

    public <T> T recordSearch(Supplier<T> operation) {
        requests.increment();
        return latency.record(operation);
    }

    public <T> T recordFts(Supplier<T> operation) {
        return ftsLatency.record(operation);
    }

    public <T> T recordSemantic(Supplier<T> operation) {
        return semanticLatency.record(operation);
    }

    public <T> T recordQueryEmbedding(Supplier<T> operation) {
        return embeddingLatency.record(operation);
    }

    public void recordClientMatches(List<ClientSearchResult> clientResults) {
        if (clientResults.isEmpty()) {
            noClientMatches.increment();
            return;
        }
        if (clientResults.stream().anyMatch(result -> result.matchType() == ClientSearchResult.MatchType.EXACT)) {
            exactClientMatches.increment();
        }
        if (clientResults.stream().anyMatch(result -> result.matchType() == ClientSearchResult.MatchType.FUZZY)) {
            fuzzyClientMatches.increment();
        }
    }

    public void recordLexicalCandidates(int count) {
        lexicalCandidates.record(count);
    }

    public void recordSemanticCandidates(int count) {
        semanticCandidates.record(count);
    }

    public void recordResults(int count) {
        results.record(count);
        if (count == 0) {
            zeroResults.increment();
        }
    }

    private Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name)
                .description(description)
                .register(registry);
    }

    private Timer timer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }

    private DistributionSummary summary(MeterRegistry registry, String name, String description) {
        return DistributionSummary.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }

    private DistributionSummary summary(
            MeterRegistry registry,
            String name,
            String description,
            double maximumExpectedValue
    ) {
        return DistributionSummary.builder(name)
                .description(description)
                .maximumExpectedValue(maximumExpectedValue)
                .publishPercentileHistogram()
                .register(registry);
    }
}
