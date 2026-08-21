package com.nevis.search;

import com.nevis.search.application.ClientService;
import com.nevis.search.application.DocumentService;
import com.nevis.search.application.embedding.EmbeddingVector;
import com.nevis.search.application.evaluation.EvaluationMetadataService;
import com.nevis.search.application.evaluation.EvaluationSemanticSearchResult;
import com.nevis.search.application.evaluation.SemanticRetrievalMode;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.EvaluationSemanticSearchPort;
import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("evaluation")
@Testcontainers(disabledWithoutDocker = true)
class EvaluationPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("nevis_evaluation_test")
            .withUsername("nevis")
            .withPassword("nevis");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("nevis.evaluation.embedding-model", () -> "MINILM");
    }

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ClientService clientService;

    @Autowired
    DocumentService documentService;

    @Autowired
    EmbeddingPort embeddingPort;

    @Autowired
    SemanticDocumentSearchPort productionSemanticSearch;

    @Autowired
    EvaluationSemanticSearchPort evaluationSemanticSearch;

    @Autowired
    EvaluationMetadataService metadataService;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE document_chunks, documents, clients").update();
    }

    @Test
    void evaluationHnswMatchesProductionAndExactCollapsesChunksToBestDocumentScore() {
        Client client = clientService.create("Evaluation", "Tester", "evaluation@example.com", null);
        Document relevant = documentService.create(
                client.id(), "Residential evidence", "Electricity statement for 10 King Street"
        );
        documentService.create(client.id(), "Cooking", "Olive oil, tomato and basil recipe");
        EmbeddingVector query = embeddingPort.embedQuery("evidence of where the customer lives");
        String queryVector = vectorLiteral(query);

        jdbcClient.sql("""
                        INSERT INTO document_chunks (document_id, chunk_index, content, embedding)
                        SELECT :documentId, value, 'duplicate relevant chunk', CAST(:embedding AS vector)
                        FROM generate_series(1, 300) AS value
                        """)
                .param("documentId", relevant.id())
                .param("embedding", queryVector)
                .update();
        jdbcClient.sql("ANALYZE document_chunks").update();

        List<DocumentSearchResult> production = productionSemanticSearch.search(query, 50, 250, 500, -1.0);
        EvaluationSemanticSearchResult hnsw = evaluationSemanticSearch.search(
                query, SemanticRetrievalMode.HNSW, 50, 250, 500, -1.0
        );
        EvaluationSemanticSearchResult exact = evaluationSemanticSearch.search(
                query, SemanticRetrievalMode.EXACT, 50, 250, 500, -1.0
        );

        assertThat(hnsw.documents()).extracting(result -> result.document().id())
                .containsExactlyElementsOf(production.stream().map(result -> result.document().id()).toList());
        assertThat(hnsw.documents()).extracting(DocumentSearchResult::relevance)
                .containsExactlyElementsOf(production.stream().map(DocumentSearchResult::relevance).toList());
        assertThat(exact.documents().getFirst().document().id()).isEqualTo(relevant.id());
        assertThat(exact.documents().getFirst().relevance()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(exact.chunks()).hasSize(250);
        assertThat(exact.chunks()).extracting(EvaluationSemanticSearchResult.ChunkHit::documentId)
                .containsOnly(relevant.id());
        assertThat(exact.chunks()).extracting(EvaluationSemanticSearchResult.ChunkHit::rank)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 250).boxed().toList());
    }

    @Test
    void exactPlanAvoidsHnswAndHnswPlanUsesItAtMeaningfulScale() {
        Client client = clientService.create("Plan", "Tester", "plan@example.com", null);
        Document document = documentService.create(client.id(), "Plan document", "Plan body");
        EmbeddingVector query = embeddingPort.embedQuery("plan query");
        String queryVector = vectorLiteral(query);

        jdbcClient.sql("DROP INDEX document_chunks_embedding_hnsw_idx").update();
        jdbcClient.sql("""
                        INSERT INTO document_chunks (document_id, chunk_index, content, embedding)
                        SELECT :documentId, value, 'scale chunk ' || value, CAST(:embedding AS vector)
                        FROM generate_series(1, 50000) AS value
                        """)
                .param("documentId", document.id())
                .param("embedding", queryVector)
                .update();
        jdbcClient.sql("""
                CREATE INDEX document_chunks_embedding_hnsw_idx
                ON document_chunks USING hnsw (embedding vector_cosine_ops)
                """).update();
        jdbcClient.sql("ANALYZE document_chunks").update();

        String exactPlan = evaluationSemanticSearch.explain(query, SemanticRetrievalMode.EXACT, 250, 500);
        String hnswPlan = evaluationSemanticSearch.explain(query, SemanticRetrievalMode.HNSW, 250, 500);

        assertThat(exactPlan).doesNotContain("document_chunks_embedding_hnsw_idx");
        assertThat(hnswPlan).contains("document_chunks_embedding_hnsw_idx");
        assertThat(metadataService.metadata().database().chunkCount()).isEqualTo(50001);
        assertThat(metadataService.metadata().database().hnswIndexBytes()).isPositive();
    }

    private String vectorLiteral(EmbeddingVector embedding) {
        float[] values = embedding.values();
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append(values[index]);
        }
        return result.append(']').toString();
    }
}
