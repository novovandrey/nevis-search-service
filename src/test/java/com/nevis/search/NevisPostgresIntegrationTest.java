package com.nevis.search;

import com.nevis.search.application.ClientService;
import com.nevis.search.application.ClientSearchQueryNormalizer;
import com.nevis.search.application.DocumentService;
import com.nevis.search.application.QueryExpander;
import com.nevis.search.application.QueryNormalizer;
import com.nevis.search.application.SearchService;
import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.application.port.EmbeddingPort;
import com.nevis.search.application.port.SemanticDocumentSearchPort;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NevisPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("nevis_test")
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
    SearchService searchService;

    @Autowired
    QueryNormalizer queryNormalizer;

    @Autowired
    ClientSearchQueryNormalizer clientSearchQueryNormalizer;

    @Autowired
    QueryExpander queryExpander;

    @Autowired
    ClientSearchPort clientSearchPort;

    @Autowired
    DocumentSearchPort documentSearchPort;

    @Autowired
    SemanticDocumentSearchPort semanticDocumentSearchPort;

    @Autowired
    EmbeddingPort embeddingPort;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE documents, clients").update();
    }

    @Test
    void migrationsCreateGeneratedSearchVectorAndForeignKey() {
        assertThat(jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm')")
                .query(Boolean.class)
                .single())
                .isTrue();
        assertThat(jdbcClient.sql("""
                        SELECT attgenerated
                        FROM pg_attribute
                        WHERE attrelid = 'clients'::regclass AND attname = 'company_search_key'
                        """)
                .query(String.class)
                .single())
                .isEqualTo("s");
        assertThat(jdbcClient.sql("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public' AND tablename = 'clients'
                        ORDER BY indexname
                        """)
                .query(String.class)
                .list())
                .contains("clients_company_search_key_exact_idx", "clients_company_search_key_trgm_idx");
        List<String> mappingColumns = jdbcClient.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'search_term_mapping'
                        ORDER BY ordinal_position
                        """)
                .query(String.class)
                .list();
        assertThat(mappingColumns).containsExactly("group_key", "term");

        String mappingPrimaryKey = jdbcClient.sql("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conrelid = 'public.search_term_mapping'::regclass
                          AND contype = 'p'
                        """)
                .query(String.class)
                .single();
        assertThat(mappingPrimaryKey).isEqualTo("PRIMARY KEY (group_key, term)");
        assertThat(jdbcClient.sql("""
                        SELECT term
                        FROM search_term_mapping
                        WHERE group_key = 'proof_of_address'
                        ORDER BY term
                        """)
                .query(String.class)
                .list())
                .containsExactly(
                        "address proof", "bank statement", "proof of address",
                        "proof of residency", "utility bill"
                );
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO search_term_mapping (group_key, term)
                        VALUES ('proof_of_address', 'address proof')
                        """).update())
                .isInstanceOf(DataAccessException.class);

        Client client = clientService.create("Ada", "Lovelace", "ada@example.com", "UK");
        Document document = documentService.create(client.id(), "Utility Bill", "Residential address");

        String vector = jdbcClient.sql("SELECT search_vector::text FROM documents WHERE id = :id")
                .param("id", document.id())
                .query(String.class)
                .single();

        assertThat(vector).contains("'util'", "'bill'", "'residenti'", "'address'");
        assertThat(jdbcClient.sql("SELECT vector_dims(embedding) FROM documents WHERE id = :id")
                .param("id", document.id())
                .query(Integer.class)
                .single())
                .isEqualTo(384);
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO documents (id, client_id, title, content, created_at)
                        VALUES (:id, :clientId, 'Invalid', 'Invalid', now())
                        """)
                .param("id", UUID.randomUUID())
                .param("clientId", UUID.randomUUID())
                .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void clientSearchUsesExactAndFuzzyCompanyKeysWithoutLocalPartOrSubstringMatches() {
        Client exact = clientService.create(
                "Exact", "Hewlett", "exact@hewlettpackard.com", "UK"
        );
        Client fuzzyExtraCharacter = clientService.create(
                "Fuzzy", "Extra", "user@hewlettpackarrd.io", null
        );
        Client fuzzyMissingCharacter = clientService.create(
                "Fuzzy", "Missing", "user@hewlettpackerd.io", null
        );
        Client microsoft = clientService.create("Microsoft", "Person", "other@microsoft.com", null);
        Client localPart = clientService.create("Local", "Part", "hewlettpackard.employee@gmail.com", null);
        clientService.create("Near", "Match", "near@myhewlettpackard.com", null);
        jdbcClient.sql("""
                        INSERT INTO clients (id, first_name, last_name, email, country_of_residence)
                        VALUES (:id, 'Malformed', 'Email', 'unexpected-email-value', NULL)
                        """)
                .param("id", UUID.randomUUID())
                .update();
        Client subdomain = clientService.create(
                "Subdomain", "Example", "user@sub.hewlettpackard.co.uk", null
        );

        assertThat(jdbcClient.sql("SELECT company_search_key FROM clients WHERE id = :id")
                .param("id", exact.id())
                .query(String.class)
                .single())
                .isEqualTo("hewlettpackard");
        assertThat(jdbcClient.sql("SELECT company_search_key FROM clients WHERE id = :id")
                .param("id", subdomain.id())
                .query(String.class)
                .single())
                .isEqualTo("sub.hewlettpackard.co");

        for (String query : List.of("Hewlett Packard", "hewlett packard", "HEWLETT PACKARD", "hewlettpackard")) {
            assertThat(clientSearchPort.search(clientSearchQueryNormalizer.normalize(query)))
                    .extracting(result -> result.client().id())
                    .contains(exact.id(), fuzzyExtraCharacter.id(), fuzzyMissingCharacter.id())
                    .doesNotContain(microsoft.id(), localPart.id());
        }

        List<UUID> fuzzyIds = clientSearchPort.search(clientSearchQueryNormalizer.normalize("Hewlett Packard"))
                .stream()
                .map(result -> result.client().id())
                .toList();
        float extraSimilarity = jdbcClient.sql("SELECT similarity('hewlettpackarrd', 'hewlettpackard')")
                .query(Float.class)
                .single();
        float missingSimilarity = jdbcClient.sql("SELECT similarity('hewlettpackerd', 'hewlettpackard')")
                .query(Float.class)
                .single();
        assertThat(fuzzyIds.getFirst()).isEqualTo(exact.id());
        assertThat(fuzzyIds).contains(fuzzyExtraCharacter.id(), fuzzyMissingCharacter.id());
        if (extraSimilarity > missingSimilarity) {
            assertThat(fuzzyIds.indexOf(fuzzyExtraCharacter.id()))
                    .isLessThan(fuzzyIds.indexOf(fuzzyMissingCharacter.id()));
        } else {
            assertThat(fuzzyIds.indexOf(fuzzyMissingCharacter.id()))
                    .isLessThan(fuzzyIds.indexOf(fuzzyExtraCharacter.id()));
        }

        assertThat(clientSearchPort.search(clientSearchQueryNormalizer.normalize("Hewlett"))).isEmpty();
        assertThat(clientSearchPort.search(
                clientSearchQueryNormalizer.normalize("hewlettpackard.employee@gmail.com")
        )).isEmpty();
        assertThat(clientSearchPort.search(clientSearchQueryNormalizer.normalize("Hewlett' OR '1'='1"))).isEmpty();
        assertThat(clientSearchPort.search(clientSearchQueryNormalizer.normalize("he"))).isEmpty();
    }

    @Test
    void trigramSearchPlanUsesTheGinIndexAfterAnalyze() {
        clientService.create("Fuzzy", "Plan", "user@hewlettpackarrd.io", null);
        jdbcClient.sql("""
                        INSERT INTO clients (id, first_name, last_name, email, country_of_residence)
                        SELECT (substr(md5('trigram-client-' || value::text), 1, 8) || '-' ||
                                substr(md5('trigram-client-' || value::text), 9, 4) || '-' ||
                                substr(md5('trigram-client-' || value::text), 13, 4) || '-' ||
                                substr(md5('trigram-client-' || value::text), 17, 4) || '-' ||
                                substr(md5('trigram-client-' || value::text), 21, 12))::uuid,
                               'Bulk',
                               'Client ' || value,
                               'bulk-' || value || '@unrelated-company-' || value || '.example',
                               NULL
                        FROM generate_series(1, 100000) AS value
                        """).update();
        jdbcClient.sql("ANALYZE clients").update();

        String plan = String.join("\n", jdbcClient.sql("""
                        EXPLAIN (ANALYZE, COSTS OFF)
                        SELECT id
                        FROM clients
                        WHERE company_search_key % 'hewlettpackard'
                          AND company_search_key <> 'hewlettpackard'
                          AND company_search_key NOT LIKE '%hewlettpackard%'
                          AND 'hewlettpackard' NOT LIKE '%' || company_search_key || '%'
                          AND similarity(company_search_key, 'hewlettpackard') >= 0.50
                        """)
                .query(String.class)
                .list());

        assertThat(plan).contains("clients_company_search_key_trgm_idx");
    }

    @Test
    void businessTermsUseOrSemanticsAndGlobalSearchIncludesMatchingDocuments() {
        Client clientA = clientService.create("Client", "A", "a@example.com", null);
        Client clientB = clientService.create("Client", "B", "b@example.com", null);
        Document utilityBill = documentService.create(
                clientA.id(), "Utility Bill", "Electricity charges for the current residence"
        );
        Document addressProof = documentService.create(
                clientB.id(), "Address Proof Address Proof", "Utility bill and bank statement address proof"
        );

        Set<String> expanded = queryExpander.expand(queryNormalizer.normalize("bank statement"));
        assertThat(expanded).contains(
                "address proof", "proof of address", "proof of residency", "utility bill", "bank statement"
        );
        assertThat(queryExpander.expand(queryNormalizer.normalize("passport"))).containsExactly("passport");

        assertThat(searchService.search("address proof").documents())
                .extracting(result -> result.document().id())
                .contains(utilityBill.id(), addressProof.id());
    }

    @Test
    void fullTextSearchUsesStemmingRankingAndDeterministicNoResultBehavior() {
        Client client = clientService.create("Search", "Tester", "search@example.com", null);
        Document titleMatch = documentService.create(client.id(), "Passport", "Official identity record");
        Document contentMatch = documentService.create(client.id(), "Identity Record", "Contains a passport copy");

        List<DocumentSearchResult> results = documentSearchPort.search(Set.of("passports"), 50);

        assertThat(results).extracting(result -> result.document().id())
                .containsExactly(titleMatch.id(), contentMatch.id());
        assertThat(results.getFirst().relevance()).isGreaterThan(results.getLast().relevance());
        assertThat(documentSearchPort.search(Set.of("unfindable"), 50)).isEmpty();
    }

    @Test
    void semanticSearchFindsConceptualMatchWithoutExplicitTermMapping() {
        Client client = clientService.create("Semantic", "Tester", "semantic@example.com", null);
        Document electricityStatement = documentService.create(
                client.id(),
                "Monthly statement",
                "The customer receives a monthly electricity statement for the apartment at 10 King Street."
        );
        documentService.create(
                client.id(),
                "Cooking notes",
                "The recipe uses olive oil, tomatoes, basil and pasta for dinner."
        );

        String query = "evidence of where the customer lives";
        assertThat(queryExpander.expand(queryNormalizer.normalize(query))).containsExactly(query);
        assertThat(documentSearchPort.search(Set.of(query), 50)).isEmpty();

        List<DocumentSearchResult> semanticResults = semanticDocumentSearchPort.search(embeddingPort.embed(query), 1, 0.30);

        assertThat(semanticResults).extracting(result -> result.document().id())
                .containsExactly(electricityStatement.id());
        assertThat(searchService.search(query).documents()).extracting(result -> result.document().id())
                .contains(electricityStatement.id());
    }

    @Test
    void hybridSearchReturnsDocumentOnceWhenBothRetrieversFindIt() {
        Client client = clientService.create("Hybrid", "Tester", "hybrid@example.com", null);
        Document document = documentService.create(
                client.id(), "Passport", "A scanned passport belonging to the customer"
        );

        assertThat(documentSearchPort.search(Set.of("passport"), 50))
                .extracting(result -> result.document().id()).contains(document.id());
        assertThat(semanticDocumentSearchPort.search(embeddingPort.embed("passport"), 50, 0.30))
                .extracting(result -> result.document().id()).contains(document.id());
        assertThat(searchService.search("passport").documents())
                .extracting(result -> result.document().id()).containsOnlyOnce(document.id());
    }

    @Test
    void weightedRrfRanksLiteralAddressMatchesAboveNewerBoundaryDocuments() {
        Client client = clientService.create("Ranking", "Tester", "ranking@example.com", null);
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
        String token = "29ba551f1734";
        String boundaryPrefix = "T" + token;
        Document boundary = documentService.create(
                client.id(),
                boundaryPrefix + "x".repeat(254 - boundaryPrefix.length()),
                "Title boundary 254 " + token
        );
        Document unrelated = documentService.create(
                client.id(),
                "Cooking Notes",
                "The recipe uses olive oil, tomatoes, basil and pasta for dinner."
        );

        List<DocumentSearchResult> results = searchService.search("address").documents();

        assertThat(results).extracting(result -> result.document().id())
                .contains(literalAddress.id(), manualAddress.id(), boundary.id())
                .doesNotContain(unrelated.id());
        assertThat(rankOf(results, literalAddress)).isLessThan(rankOf(results, boundary));
        assertThat(rankOf(results, manualAddress)).isLessThan(rankOf(results, boundary));
    }

    @Test
    void apiKeepsSearchQueryAndDocumentTitleLimitsConsistent() throws Exception {
        Client client = clientService.create("Length", "Tester", "length@example.com", null);
        String title = "a".repeat(255);

        String documentBody = mockMvc.perform(post("/clients/{id}/documents", client.id())
                        .contentType("application/json")
                        .content("""
                                {"title":"%s","content":"Full-length title test document"}
                                """.formatted(title)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String documentId = com.jayway.jsonpath.JsonPath.read(documentBody, "$.id");

        mockMvc.perform(get("/search").param("q", title))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("DOCUMENT"))
                .andExpect(jsonPath("$[0].id").value(documentId));

        mockMvc.perform(get("/search").param("q", "a".repeat(256)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Search query must not exceed 255 characters"));
    }

    private int rankOf(List<DocumentSearchResult> results, Document document) {
        for (int index = 0; index < results.size(); index++) {
            if (results.get(index).document().id().equals(document.id())) {
                return index + 1;
            }
        }
        return -1;
    }

    @Test
    void apiCoversCreationValidationUnknownClientAndGlobalSearch() throws Exception {
        String clientBody = mockMvc.perform(post("/clients")
                        .contentType("application/json")
                        .content("""
                                {
                                  "first_name": "Anton",
                                  "last_name": "Batiaev",
                                  "email": "anton.batiaev@neviswealth.com",
                                  "countryOfResidence": "UK"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/clients/")))
                .andExpect(jsonPath("$.first_name").value("Anton"))
                .andExpect(jsonPath("$.last_name").value("Batiaev"))
                .andExpect(jsonPath("$.email").value("anton.batiaev@neviswealth.com"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String clientId = com.jayway.jsonpath.JsonPath.read(clientBody, "$.id");

        mockMvc.perform(post("/clients/{id}/documents", clientId)
                        .contentType("application/json")
                        .content("""
                                {"title":"Utility Bill","content":"Original document content containing utility bill"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id").value(clientId))
                .andExpect(jsonPath("$.content")
                        .value("Original document content containing utility bill"))
                .andExpect(jsonPath("$.created_at").exists());

        mockMvc.perform(get("/search").param("q", "Nevis Wealth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CLIENT"))
                .andExpect(jsonPath("$[0].email").value("anton.batiaev@neviswealth.com"))
                .andExpect(jsonPath("$[0].content").doesNotExist());

        mockMvc.perform(get("/search").param("q", "Batiaev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/search").param("q", "anton.batiaev@neviswealth.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/search").param("q", "address proof"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("DOCUMENT"))
                .andExpect(jsonPath("$[0].title").value("Utility Bill"))
                .andExpect(jsonPath("$[0].content")
                        .value("Original document content containing utility bill"));

        mockMvc.perform(get("/search").param("q", "definitely missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(post("/clients")
                        .header("Accept-Language", "ru-RU")
                        .contentType("application/json")
                        .content("""
                                {"firstName":"Bad","lastName":"Email","email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("email"))
                .andExpect(jsonPath("$.violations[0].message")
                        .value("must be a well-formed email address"));

        mockMvc.perform(post("/clients/{id}/documents", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"title":"Missing","content":"Client does not exist"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/search").param("q", "   "))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/search"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/clients")
                        .contentType("application/json")
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));

        mockMvc.perform(post("/clients")
                        .contentType("application/octet-stream")
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message").value("Unsupported media type"));
    }

    @Test
    void apiRejectsNonStringJsonValuesForStringRequestFields() throws Exception {
        mockMvc.perform(post("/clients")
                        .contentType("application/json")
                        .content("""
                                {
                                  "first_name": 123,
                                  "last_name": "Smith",
                                  "email": "john@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.trace").doesNotExist());

        mockMvc.perform(post("/clients")
                        .contentType("application/json")
                        .content("""
                                {
                                  "first_name": true,
                                  "last_name": "Smith",
                                  "email": "john@example.com"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));

        Client client = clientService.create("Strict", "Types", "strict-types@example.com", null);

        mockMvc.perform(post("/clients/{id}/documents", client.id())
                        .contentType("application/json")
                        .content("""
                                {"title":123,"content":"abc"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));

        mockMvc.perform(post("/clients/{id}/documents", client.id())
                        .contentType("application/json")
                        .content("""
                                {"title":"Valid title","content":123}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));

        mockMvc.perform(post("/clients/{id}/documents", client.id())
                        .contentType("application/json")
                        .content("""
                                {"title":"123","content":"abc"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("123"))
                .andExpect(jsonPath("$.content").value("abc"));
    }

    @Test
    void openApiDocumentsJsonContractAndActualResponseStatuses() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.info.title").value("API"))
                .andExpect(jsonPath("$.info.version").value("1.0.0"))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.first_name").exists())
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.first_name.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.last_name").exists())
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.last_name.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.first_name.minLength")
                        .value(1))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.first_name.maxLength")
                        .value(100))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.last_name.minLength")
                        .value(1))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.last_name.maxLength")
                        .value(100))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.email.minLength").value(1))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.email.maxLength").value(254))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.email.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateClientRequest.properties.countryOfResidence.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateDocumentRequest.properties.title.minLength").value(1))
                .andExpect(jsonPath("$.components.schemas.CreateDocumentRequest.properties.title.maxLength").value(255))
                .andExpect(jsonPath("$.components.schemas.CreateDocumentRequest.properties.title.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.CreateDocumentRequest.properties.content.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.DocumentResponse.properties.client_id").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentResponse.properties.created_at").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentSearchResponse.properties.content").exists())
                .andExpect(jsonPath("$.components.schemas.DocumentSearchResponse.properties.content.description")
                        .value("Stored document content; populated for DOCUMENT search results only"))
                .andExpect(jsonPath("$.paths['/clients'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/clients'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/clients'].post.responses['415']").exists())
                .andExpect(jsonPath("$.paths['/clients'].post.responses['500']").exists())
                .andExpect(jsonPath("$.paths['/clients/{id}/documents'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/clients/{id}/documents'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/clients/{id}/documents'].post.responses['415']").exists())
                .andExpect(jsonPath("$.paths['/clients/{id}/documents'].post.parameters[0].name").value("id"))
                .andExpect(jsonPath("$.paths['/clients/{id}/documents'].get").doesNotExist())
                .andExpect(jsonPath("$.paths['/search'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/search'].get.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/search'].get.responses['500']").exists())
                .andExpect(jsonPath("$.paths['/search'].get.parameters.length()").value(1))
                .andExpect(jsonPath("$.paths['/search'].get.parameters[0].name").value("q"))
                .andExpect(jsonPath("$.paths['/search'].get.parameters[0].schema.maxLength").value(255));
    }
}
