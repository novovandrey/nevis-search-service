package com.nevis.search.api.evaluation;

import com.nevis.search.api.ApiExceptionHandler;
import com.nevis.search.application.evaluation.SearchEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvaluationSearchControllerValidationTest {

    private SearchEvaluationService searchEvaluationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        searchEvaluationService = Mockito.mock(SearchEvaluationService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new EvaluationSearchController(searchEvaluationService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void rejectsInvalidRequestScopedOverrides() throws Exception {
        mockMvc.perform(post("/internal/evaluation/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"passport","mode":"HYBRID","candidateLimit":0,"minimumSimilarity":1.1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.violations[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "candidateLimit", "minimumSimilarity"
                )));

        verifyNoInteractions(searchEvaluationService);
    }
}
