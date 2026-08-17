package com.nevis.search;

import com.nevis.search.application.ClientService;
import com.nevis.search.application.ClientSearchQueryNormalizer;
import com.nevis.search.application.DocumentService;
import com.nevis.search.application.QueryExpander;
import com.nevis.search.application.QueryNormalizer;
import com.nevis.search.application.SearchService;
import com.nevis.search.application.port.ClientSearchPort;
import com.nevis.search.application.port.DocumentSearchPort;
import com.nevis.search.domain.Client;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import com.nevis.search.domain.DocumentSearchScope;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
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
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6-alpine")
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
    MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE documents, clients").update();
    }

    @Test
    void migrationsCreateGeneratedSearchVectorAndForeignKey() {
        Client client = clientService.create("Ada", "Lovelace", "ada@example.com", "UK");
        Document document = documentService.create(client.id(), "Utility Bill", "Residential address");

        String vector = jdbcClient.sql("SELECT search_vector::text FROM documents WHERE id = :id")
                .param("id", document.id())
                .query(String.class)
                .single();

        assertThat(vector).contains("'util'", "'bill'", "'residenti'", "'address'");
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
    void clientSearchMatchesOnlyNormalizedCompanyDomainAndIgnoresUnexpectedEmails() {
        Client nevis = clientService.create(
                "Anton", "Batiaev", "anton.batiaev@neviswealth.com", "UK"
        );
        clientService.create("Other", "Person", "other@example.com", null);
        clientService.create("Near", "Match", "near@myneviswealth.com", null);
        jdbcClient.sql("""
                        INSERT INTO clients (id, first_name, last_name, email, country_of_residence)
                        VALUES (:id, 'Malformed', 'Email', 'unexpected-email-value', NULL)
                        """)
                .param("id", UUID.randomUUID())
                .update();

        for (String query : List.of("Nevis Wealth", "nevis wealth", "NEVIS WEALTH", "neviswealth")) {
            assertThat(clientSearchPort.search(clientSearchQueryNormalizer.normalize(query)))
                    .extracting(result -> result.client().id())
                    .containsExactly(nevis.id());
        }
        assertThat(clientSearchPort.search(clientSearchQueryNormalizer.normalize("Other Company"))).isEmpty();
        assertThat(clientSearchPort.search(
                clientSearchQueryNormalizer.normalize("ANTON.BATIAEV@NEVISWEALTH.COM")
        )).isEmpty();
        assertThat(clientSearchPort.search(clientSearchQueryNormalizer.normalize("Batiaev"))).isEmpty();
    }

    @Test
    void businessTermsUseOrSemanticsAndClientScopeNeverLeaksDocuments() {
        Client clientA = clientService.create("Client", "A", "a@example.com", null);
        Client clientB = clientService.create("Client", "B", "b@example.com", null);
        Document utilityBill = documentService.create(
                clientA.id(), "Utility Bill", "Electricity charges for the current residence"
        );
        documentService.create(
                clientB.id(), "Address Proof Address Proof", "Utility bill and bank statement address proof"
        );

        List<DocumentSearchResult> clientAResults = documentService.search(clientA.id(), "address proof", 20);
        List<DocumentSearchResult> clientBResults = documentService.search(clientB.id(), "proof of residency", 20);

        assertThat(clientAResults).extracting(result -> result.document().id()).containsExactly(utilityBill.id());
        assertThat(clientAResults).allSatisfy(result ->
                assertThat(result.document().clientId()).isEqualTo(clientA.id()));
        assertThat(clientBResults).allSatisfy(result ->
                assertThat(result.document().clientId()).isEqualTo(clientB.id()));
        assertThat(documentService.list(clientA.id(), 20, 0))
                .extracting(Document::clientId)
                .containsOnly(clientA.id());

        Set<String> expanded = queryExpander.expand(queryNormalizer.normalize("bank statement"));
        assertThat(expanded).contains(
                "address proof", "proof of address", "proof of residency", "utility bill", "bank statement"
        );
        assertThat(queryExpander.expand(queryNormalizer.normalize("passport"))).containsExactly("passport");

        assertThat(searchService.search("address proof", 20).documents())
                .extracting(result -> result.document().clientId())
                .contains(clientA.id(), clientB.id());
    }

    @Test
    void fullTextSearchUsesStemmingRankingAndDeterministicNoResultBehavior() {
        Client client = clientService.create("Search", "Tester", "search@example.com", null);
        Document titleMatch = documentService.create(client.id(), "Passport", "Official identity record");
        Document contentMatch = documentService.create(client.id(), "Identity Record", "Contains a passport copy");

        List<DocumentSearchResult> results = documentSearchPort.search(
                Set.of("passports"), new DocumentSearchScope.Client(client.id()), 20
        );

        assertThat(results).extracting(result -> result.document().id())
                .containsExactly(titleMatch.id(), contentMatch.id());
        assertThat(results.getFirst().relevance()).isGreaterThan(results.getLast().relevance());
        assertThat(documentSearchPort.search(
                Set.of("unfindable"), new DocumentSearchScope.AllClients(), 20
        )).isEmpty();
    }

    @Test
    void apiCoversCreationValidationUnknownClientScopedAndGlobalSearch() throws Exception {
        String clientBody = mockMvc.perform(post("/clients")
                        .contentType("application/json")
                        .content("""
                                {
                                  "firstName": "Anton",
                                  "lastName": "Batiaev",
                                  "email": "anton.batiaev@neviswealth.com",
                                  "countryOfResidence": "UK"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/clients/")))
                .andExpect(jsonPath("$.email").value("anton.batiaev@neviswealth.com"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String clientId = com.jayway.jsonpath.JsonPath.read(clientBody, "$.id");

        mockMvc.perform(post("/clients/{clientId}/documents", clientId)
                        .contentType("application/json")
                        .content("""
                                {"title":"Utility Bill","content":"Electricity bill for this address"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value(clientId));

        mockMvc.perform(get("/clients/{clientId}/documents", clientId).param("q", "address proof"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Utility Bill"))
                .andExpect(jsonPath("$[0].relevance").isNumber());

        mockMvc.perform(get("/search").param("q", "Nevis Wealth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", hasItem("CLIENT")));

        mockMvc.perform(get("/search").param("q", "Batiaev"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/search").param("q", "anton.batiaev@neviswealth.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get("/search").param("q", "address proof"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", hasItem("DOCUMENT")));

        mockMvc.perform(get("/search").param("q", "definitely missing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(post("/clients")
                        .contentType("application/json")
                        .content("""
                                {"firstName":"Bad","lastName":"Email","email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].field").value("email"));

        mockMvc.perform(post("/clients/{clientId}/documents", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"title":"Missing","content":"Client does not exist"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/search").param("q", "   "))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/clients")
                        .contentType("application/json")
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));
    }
}
