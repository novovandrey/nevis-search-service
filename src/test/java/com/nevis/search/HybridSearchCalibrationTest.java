package com.nevis.search;

import com.nevis.search.application.ClientService;
import com.nevis.search.application.DocumentService;
import com.nevis.search.application.HybridDocumentSearchMerger;
import com.nevis.search.application.QueryExpander;
import com.nevis.search.application.QueryNormalizer;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HybridSearchCalibrationTest {

    private static final int CANDIDATE_LIMIT = 50;
    private static final int RRF_K = 60;
    private static final List<Double> THRESHOLDS = List.of(0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60);
    private static final List<Double> LEXICAL_WEIGHTS = List.of(1.0, 1.25, 1.5, 2.0);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("nevis_calibration")
            .withUsername("nevis")
            .withPassword("nevis");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ClientService clientService;

    @Autowired
    DocumentService documentService;

    @Autowired
    QueryNormalizer queryNormalizer;

    @Autowired
    QueryExpander queryExpander;

    @Autowired
    DocumentSearchPort documentSearchPort;

    @Autowired
    SemanticDocumentSearchPort semanticDocumentSearchPort;

    @Autowired
    EmbeddingPort embeddingPort;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE document_chunks, documents, clients").update();
    }

    @Test
    void calibratesWeightedRrfFromMeasuredLexicalAndSemanticScores() {
        Corpus corpus = createCorpus();
        Map<String, Retrieval> retrievals = collectRetrievals(corpus.relevanceByQuery().keySet());

        List<Calibration> calibrations = new ArrayList<>();
        for (double threshold : THRESHOLDS) {
            for (double lexicalWeight : LEXICAL_WEIGHTS) {
                calibrations.add(evaluate(corpus, retrievals, threshold, lexicalWeight));
            }
        }

        Calibration winner = calibrations.stream()
                .filter(calibration -> calibration.valid() && calibration.lexicalWeight() > 1.0)
                .max(Comparator
                        .comparingDouble(Calibration::macroF1)
                        .thenComparingDouble(Calibration::macroPrecision)
                        .thenComparingDouble(Calibration::macroRecall)
                        .thenComparing(Calibration::threshold, Comparator.reverseOrder())
                        .thenComparing(Calibration::lexicalWeight, Comparator.reverseOrder()))
                .orElseThrow(() -> new AssertionError("No calibration candidate met the ranking constraints"));

        printReport("baseline", evaluate(corpus, retrievals, 0.30, 1.0), corpus, retrievals);
        printReport("winner", winner, corpus, retrievals);

        assertThat(winner.valid()).isTrue();
        assertThat(winner.threshold()).isEqualTo(0.30);
        assertThat(winner.lexicalWeight()).isEqualTo(1.25);
        assertThat(winner.macroRecall()).isGreaterThan(0.85);
    }

    private Corpus createCorpus() {
        Client client = clientService.create("Calibration", "Corpus", "calibration@example.com", "UK");
        Document manualAddress = documentService.create(
                client.id(),
                "Manual Browser Utility Bill",
                "Electricity statement for the current residential address"
        );
        Document literalAddress = documentService.create(
                client.id(),
                "Residential Address Confirmation",
                "The customer's residential address has been verified."
        );
        Document electricityStatement = documentService.create(
                client.id(),
                "Monthly electricity statement",
                "The customer receives a monthly electricity statement for the apartment at 10 King Street."
        );
        Document passport = documentService.create(
                client.id(),
                "Travel Identity Record",
                "A government-issued passport identifies the customer for international travel."
        );
        Document investmentRisk = documentService.create(
                client.id(),
                "Quarterly Risk Review",
                "The investments are exposed to market volatility and potential capital losses."
        );
        Document portfolio = documentService.create(
                client.id(),
                "Investment Holdings Overview",
                "The account allocation includes equities, government bonds and cash."
        );
        Document taxStatement = documentService.create(
                client.id(),
                "Annual Revenue Declaration",
                "Statement of taxable income, allowances and deductions for the financial year."
        );
        String boundaryToken = "29ba551f1734";
        String boundaryPrefix = "T" + boundaryToken;
        Document boundary = documentService.create(
                client.id(),
                boundaryPrefix + "x".repeat(254 - boundaryPrefix.length()),
                "Title boundary 254 " + boundaryToken
        );
        Document cooking = documentService.create(
                client.id(),
                "Cooking Notes",
                "The recipe uses olive oil, tomatoes, basil and pasta for dinner."
        );

        Set<UUID> addressDocuments = linkedIds(manualAddress, literalAddress, electricityStatement);
        Map<String, Set<UUID>> relevance = new LinkedHashMap<>();
        relevance.put("address", addressDocuments);
        relevance.put("address proof", addressDocuments);
        relevance.put("proof of address", addressDocuments);
        relevance.put("proof of residence", addressDocuments);
        relevance.put("residential address", linkedIds(manualAddress, literalAddress));
        relevance.put("utility bill", linkedIds(manualAddress, electricityStatement));
        relevance.put("electricity bill", linkedIds(manualAddress, electricityStatement));
        relevance.put("passport", linkedIds(passport));
        relevance.put("investment risk", linkedIds(investmentRisk));
        relevance.put("portfolio", linkedIds(portfolio));
        relevance.put("tax statement", linkedIds(taxStatement));
        relevance.put("evidence of where the customer lives", linkedIds(electricityStatement));

        return new Corpus(relevance, Set.of(boundary.id(), cooking.id()));
    }

    private Map<String, Retrieval> collectRetrievals(Set<String> queries) {
        Map<String, Retrieval> retrievals = new LinkedHashMap<>();
        for (String query : queries) {
            List<DocumentSearchResult> lexical = documentSearchPort.search(
                    queryExpander.expand(queryNormalizer.normalize(query)), CANDIDATE_LIMIT
            );
            List<DocumentSearchResult> semantic = semanticDocumentSearchPort.search(
                    embeddingPort.embed(query), CANDIDATE_LIMIT, 250, 500, -1.0
            );
            retrievals.put(query, new Retrieval(lexical, semantic));
        }
        return retrievals;
    }

    private Calibration evaluate(
            Corpus corpus,
            Map<String, Retrieval> retrievals,
            double threshold,
            double lexicalWeight
    ) {
        HybridDocumentSearchMerger merger = new HybridDocumentSearchMerger(
                new SemanticSearchProperties(CANDIDATE_LIMIT, 250, 500, RRF_K, threshold, lexicalWeight, 1.0),
                new SearchProperties(255, 50)
        );
        Map<String, List<DocumentSearchResult>> mergedByQuery = new LinkedHashMap<>();
        double precision = 0;
        double recall = 0;
        double f1 = 0;
        boolean valid = true;

        for (Map.Entry<String, Set<UUID>> entry : corpus.relevanceByQuery().entrySet()) {
            Retrieval retrieval = retrievals.get(entry.getKey());
            List<DocumentSearchResult> semantic = retrieval.semantic().stream()
                    .filter(result -> result.relevance() >= threshold)
                    .toList();
            List<DocumentSearchResult> merged = merger.merge(retrieval.lexical(), semantic);
            mergedByQuery.put(entry.getKey(), merged);

            Set<UUID> relevant = entry.getValue();
            List<DocumentSearchResult> topFive = merged.stream().limit(5).toList();
            long hits = topFive.stream().filter(result -> relevant.contains(result.document().id())).count();
            double queryPrecision = topFive.isEmpty() ? 0 : (double) hits / topFive.size();
            double queryRecall = (double) hits / relevant.size();
            double queryF1 = queryPrecision + queryRecall == 0
                    ? 0
                    : 2 * queryPrecision * queryRecall / (queryPrecision + queryRecall);
            precision += queryPrecision;
            recall += queryRecall;
            f1 += queryF1;

            int firstRelevantRank = rankOfAny(merged, relevant);
            valid &= firstRelevantRank > 0 && firstRelevantRank <= 5;

            Set<UUID> lexicalRelevant = retrieval.lexical().stream()
                    .map(result -> result.document().id())
                    .filter(relevant::contains)
                    .collect(Collectors.toSet());
            for (UUID negativeId : corpus.negativeIds()) {
                int negativeRank = rankOf(merged, negativeId);
                valid &= negativeRank < 0 || negativeRank > firstRelevantRank;
                valid &= negativeRank < 0 || lexicalRelevant.stream()
                        .allMatch(id -> {
                            int rank = rankOf(merged, id);
                            return rank > 0 && rank < negativeRank;
                        });
            }
        }

        int queryCount = corpus.relevanceByQuery().size();
        return new Calibration(
                threshold,
                lexicalWeight,
                precision / queryCount,
                recall / queryCount,
                f1 / queryCount,
                valid,
                mergedByQuery
        );
    }

    private void printReport(
            String label,
            Calibration calibration,
            Corpus corpus,
            Map<String, Retrieval> retrievals
    ) {
        System.out.printf(
                "%nCALIBRATION %s threshold=%.2f lexicalWeight=%.2f vectorWeight=1.00 "
                        + "precision@5=%.4f recall@5=%.4f f1@5=%.4f valid=%s%n",
                label,
                calibration.threshold(),
                calibration.lexicalWeight(),
                calibration.macroPrecision(),
                calibration.macroRecall(),
                calibration.macroF1(),
                calibration.valid()
        );
        System.out.println("query | document | lexicalScore | semanticSimilarity | lexicalRank | semanticRank | finalScore");
        for (Map.Entry<String, List<DocumentSearchResult>> entry : calibration.mergedByQuery().entrySet()) {
            Retrieval retrieval = retrievals.get(entry.getKey());
            Map<UUID, DocumentSearchResult> lexicalById = byId(retrieval.lexical());
            Map<UUID, DocumentSearchResult> semanticById = byId(retrieval.semantic());
            for (DocumentSearchResult result : entry.getValue().stream().limit(5).toList()) {
                UUID id = result.document().id();
                System.out.printf(
                        "%s | %s | %s | %s | %s | %s | %.8f%n",
                        entry.getKey(),
                        abbreviate(result.document().title()),
                        scoreOf(lexicalById.get(id)),
                        scoreOf(semanticById.get(id)),
                        rankText(retrieval.lexical(), id),
                        rankText(retrieval.semantic(), id),
                        result.relevance()
                );
            }
        }
        assertThat(calibration.mergedByQuery().get("address"))
                .extracting(result -> result.document().id())
                .containsAnyElementsOf(corpus.relevanceByQuery().get("address"));
    }

    private static Set<UUID> linkedIds(Document... documents) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (Document document : documents) {
            ids.add(document.id());
        }
        return ids;
    }

    private static int rankOfAny(List<DocumentSearchResult> results, Set<UUID> ids) {
        if (ids.isEmpty()) {
            return -1;
        }
        for (int index = 0; index < results.size(); index++) {
            if (ids.contains(results.get(index).document().id())) {
                return index + 1;
            }
        }
        return -1;
    }

    private static int rankOf(List<DocumentSearchResult> results, UUID id) {
        return rankOfAny(results, Set.of(id));
    }

    private static String rankText(List<DocumentSearchResult> results, UUID id) {
        int rank = rankOf(results, id);
        return rank < 0 ? "-" : Integer.toString(rank);
    }

    private static Map<UUID, DocumentSearchResult> byId(List<DocumentSearchResult> results) {
        return results.stream().collect(Collectors.toMap(
                result -> result.document().id(),
                Function.identity()
        ));
    }

    private static String scoreOf(DocumentSearchResult result) {
        return result == null ? "-" : "%.6f".formatted(result.relevance());
    }

    private static String abbreviate(String title) {
        return title.length() <= 48 ? title : title.substring(0, 45) + "...";
    }

    private record Corpus(Map<String, Set<UUID>> relevanceByQuery, Set<UUID> negativeIds) {
    }

    private record Retrieval(List<DocumentSearchResult> lexical, List<DocumentSearchResult> semantic) {
    }

    private record Calibration(
            Double threshold,
            Double lexicalWeight,
            double macroPrecision,
            double macroRecall,
            double macroF1,
            boolean valid,
            Map<String, List<DocumentSearchResult>> mergedByQuery
    ) {
    }
}
