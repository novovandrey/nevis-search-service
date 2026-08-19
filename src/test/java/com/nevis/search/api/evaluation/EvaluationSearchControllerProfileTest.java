package com.nevis.search.api.evaluation;

import com.nevis.search.application.evaluation.SearchEvaluationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationSearchControllerProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void isNotCreatedOutsideTheEvaluationProfile() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(EvaluationSearchController.class));
    }

    @Test
    void isCreatedWhenTheEvaluationProfileIsActive() {
        contextRunner.withPropertyValues("spring.profiles.active=evaluation")
                .run(context -> assertThat(context).hasSingleBean(EvaluationSearchController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(EvaluationSearchController.class)
    static class TestConfiguration {

        @Bean
        SearchEvaluationService searchEvaluationService() {
            return Mockito.mock(SearchEvaluationService.class);
        }
    }
}
