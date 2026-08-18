package com.nevis.search.application;

import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.config.SearchProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientSearchQueryNormalizerTest {

    private final ClientSearchQueryNormalizer normalizer =
            new ClientSearchQueryNormalizer(new SearchProperties(255));

    @Test
    void normalizesEquivalentCompanyQueriesToTheSameKey() {
        assertThat(normalizer.normalize("Nevis Wealth").value()).isEqualTo("neviswealth");
        assertThat(normalizer.normalize("nevis wealth").value()).isEqualTo("neviswealth");
        assertThat(normalizer.normalize("NEVIS WEALTH").value()).isEqualTo("neviswealth");
        assertThat(normalizer.normalize("neviswealth").value()).isEqualTo("neviswealth");
    }

    @Test
    void removesWhitespaceButDoesNotIntroduceFuzzyPunctuationRules() {
        assertThat(normalizer.normalize("  Nevis\t Wealth  ").value()).isEqualTo("neviswealth");
        assertThat(normalizer.normalize("Nevis-Wealth").value()).isEqualTo("nevis-wealth");
    }

    @Test
    void accepts255CharacterQueriesAndRejectsLongerQueries() {
        assertThatThrownBy(() -> normalizer.normalize("  "))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> normalizer.normalize("..."))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(normalizer.normalize("a".repeat(255)).value()).hasSize(255);
        assertThatThrownBy(() -> normalizer.normalize("a".repeat(256)))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Search query must not exceed 255 characters");
    }
}
