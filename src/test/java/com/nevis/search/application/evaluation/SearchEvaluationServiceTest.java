package com.nevis.search.application.evaluation;

import com.nevis.search.application.HybridDocumentSearchMerger;
import com.nevis.search.application.QueryExpander;
import com.nevis.search.application.QueryNormalizer;
import com.nevis.search.application.evaluation.SearchEvaluationOverrides.SearchEvaluationParameters;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.EvaluationSemanticSearchPort;
import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchEvaluationServiceTest {

    private final SearchProperties searchProperties = new SearchProperties(255, 50);
    private final SemanticSearchProperties semanticProperties = new SemanticSearchProperties(
            50, 250, 500, 60, 0.30, 1.25, 1.0
    );

    @Test
    void executesHybridSearchWithRequestScopedOverridesAndDiagnosticRankings() {
        Document lexicalOnly = document("00000000-0000-0000-0000-000000000001");
        Document inBoth = document("00000000-0000-0000-0000-000000000002");
        Document semanticOnly = document("00000000-0000-0000-0000-000000000003");
        AtomicReference<Set<String>> terms = new AtomicReference<>();
        AtomicReference<Integer> lexicalLimit = new AtomicReference<>();
        AtomicReference<Integer> semanticLimit = new AtomicReference<>();
        AtomicReference<Integer> chunkLimit = new AtomicReference<>();
        AtomicReference<Integer> efSearch = new AtomicReference<>();
        AtomicReference<Double> minimumSimilarity = new AtomicReference<>();

        SearchEvaluationService service = service(
                (expandedTerms, limit) -> {
                    terms.set(expandedTerms);
                    lexicalLimit.set(limit);
                    return List.of(
                            new DocumentSearchResult(lexicalOnly, 0.9),
                            new DocumentSearchResult(inBoth, 0.8)
                    );
                },
                semanticSearch((embedding, retrievalMode, documentLimit, rawChunkLimit, ef, threshold) -> {
                    semanticLimit.set(documentLimit);
                    chunkLimit.set(rawChunkLimit);
                    efSearch.set(ef);
                    minimumSimilarity.set(threshold);
                    return new EvaluationSemanticSearchResult(
                            List.of(
                                    new DocumentSearchResult(semanticOnly, 0.7),
                                    new DocumentSearchResult(inBoth, 0.6)
                            ),
                            List.of(
                                    new EvaluationSemanticSearchResult.ChunkHit(semanticOnly.id(), 2, 1, 0.7),
                                    new EvaluationSemanticSearchResult.ChunkHit(inBoth.id(), 0, 2, 0.6)
                            ),
                            4,
                            2
                    );
                }),
                embeddings()
        );

        SearchEvaluationResult result = service.evaluate(
                "Address Proof",
                SearchEvaluationMode.HYBRID,
                SemanticRetrievalMode.EXACT,
                new SearchEvaluationOverrides(20, 100, 250, 10, 0.20, 1.5, 0.75)
        );

        assertThat(result.query()).isEqualTo("address proof");
        assertThat(result.parameters()).isEqualTo(
                new SearchEvaluationParameters(20, 100, 250, 10, 0.20, 1.5, 0.75)
        );
        assertThat(result.semanticRetrieval()).isEqualTo(SemanticRetrievalMode.EXACT);
        assertThat(terms.get()).containsExactlyInAnyOrder("address proof", "utility bill");
        assertThat(lexicalLimit.get()).isEqualTo(20);
        assertThat(semanticLimit.get()).isEqualTo(20);
        assertThat(chunkLimit.get()).isEqualTo(100);
        assertThat(efSearch.get()).isEqualTo(250);
        assertThat(minimumSimilarity.get()).isEqualTo(0.20);
        assertThat(result.lexical()).extracting(ranking -> ranking.document().id())
                .containsExactly(lexicalOnly.id(), inBoth.id());
        assertThat(result.semantic()).extracting(ranking -> ranking.document().id())
                .containsExactly(semanticOnly.id(), inBoth.id());
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.diagnostics().distinctDocuments()).isEqualTo(2);
        assertThat(result.fused()).extracting(ranking -> ranking.document().id())
                .containsExactly(inBoth.id(), lexicalOnly.id(), semanticOnly.id());
        assertThat(result.fused().getFirst().score()).isEqualTo(1.5 / 12 + 0.75 / 12);
        assertThat(result.timings().lexicalMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.timings().semanticMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.timings().fusionMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.timings().totalMs()).isGreaterThanOrEqualTo(0);
        assertThat(semanticProperties).isEqualTo(
                new SemanticSearchProperties(50, 250, 500, 60, 0.30, 1.25, 1.0)
        );
    }

    @Test
    void executesOnlyTheSelectedRetrieverForLexicalAndSemanticModes() {
        Document document = document("00000000-0000-0000-0000-000000000011");
        AtomicInteger lexicalCalls = new AtomicInteger();
        AtomicInteger semanticCalls = new AtomicInteger();
        AtomicInteger embeddingCalls = new AtomicInteger();
        SearchEvaluationService service = service(
                (terms, limit) -> {
                    lexicalCalls.incrementAndGet();
                    return List.of(new DocumentSearchResult(document, 0.9));
                },
                semanticSearch((embedding, retrievalMode, documentLimit, chunkLimit, efSearch, threshold) -> {
                    semanticCalls.incrementAndGet();
                    return new EvaluationSemanticSearchResult(
                            List.of(new DocumentSearchResult(document, 0.7)), List.of(), 1, 0
                    );
                }),
                embeddings(embeddingCalls)
        );

        SearchEvaluationResult lexical = service.evaluate(
                "passport", SearchEvaluationMode.LEXICAL, SemanticRetrievalMode.HNSW,
                new SearchEvaluationOverrides(null, null, null, null, null, null, null)
        );
        SearchEvaluationResult semantic = service.evaluate(
                "passport", SearchEvaluationMode.SEMANTIC, SemanticRetrievalMode.HNSW,
                new SearchEvaluationOverrides(null, null, null, null, null, null, null)
        );

        assertThat(lexical.lexical()).hasSize(1);
        assertThat(lexical.semantic()).isEmpty();
        assertThat(lexical.fused()).isEmpty();
        assertThat(semantic.lexical()).isEmpty();
        assertThat(semantic.semantic()).hasSize(1);
        assertThat(semantic.fused()).isEmpty();
        assertThat(lexicalCalls).hasValue(1);
        assertThat(semanticCalls).hasValue(1);
        assertThat(embeddingCalls).hasValue(1);
    }

    private SearchEvaluationService service(
            DocumentSearchPort documentSearchPort,
            EvaluationSemanticSearchPort semanticDocumentSearchPort,
            EmbeddingPort embeddingPort
    ) {
        return new SearchEvaluationService(
                new QueryNormalizer(searchProperties),
                new QueryExpander(query -> Set.of("utility bill")),
                documentSearchPort,
                semanticDocumentSearchPort,
                embeddingPort,
                new HybridDocumentSearchMerger(semanticProperties, searchProperties),
                semanticProperties
        );
    }

    private EvaluationSemanticSearchPort semanticSearch(SemanticSearchFunction search) {
        return new EvaluationSemanticSearchPort() {
            @Override
            public EvaluationSemanticSearchResult search(
                    EmbeddingVector queryEmbedding,
                    SemanticRetrievalMode retrievalMode,
                    int documentCandidateLimit,
                    int chunkCandidateLimit,
                    int hnswEfSearch,
                    double minimumSimilarity
            ) {
                return search.apply(
                        queryEmbedding, retrievalMode, documentCandidateLimit,
                        chunkCandidateLimit, hnswEfSearch, minimumSimilarity
                );
            }

            @Override
            public String explain(
                    EmbeddingVector queryEmbedding,
                    SemanticRetrievalMode retrievalMode,
                    int chunkCandidateLimit,
                    int hnswEfSearch
            ) {
                return "[]";
            }
        };
    }

    private EmbeddingPort embeddings() {
        return embeddings(new AtomicInteger());
    }

    private EmbeddingPort embeddings(AtomicInteger queryCalls) {
        EmbeddingModelCapabilities capabilities = new EmbeddingModelCapabilities("test", 384, 510);
        return new EmbeddingPort() {
            @Override
            public EmbeddingVector embedQuery(String text) {
                queryCalls.incrementAndGet();
                return EmbeddingVector.of(new float[384], capabilities);
            }

            @Override
            public EmbeddingVector embedPassage(String text) {
                throw new AssertionError("Evaluation search must not embed passages");
            }
        };
    }

    @FunctionalInterface
    private interface SemanticSearchFunction {
        EvaluationSemanticSearchResult apply(
                EmbeddingVector embedding,
                SemanticRetrievalMode retrievalMode,
                int documentLimit,
                int chunkLimit,
                int efSearch,
                double minimumSimilarity
        );
    }

    private Document document(String id) {
        return new Document(UUID.fromString(id), UUID.randomUUID(), "Document", "Contents", Instant.parse("2026-08-20T00:00:00Z"));
    }
}
