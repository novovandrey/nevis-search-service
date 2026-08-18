package com.nevis.search.application;

import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.config.SearchProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryNormalizerTest {

    private final QueryNormalizer normalizer = new QueryNormalizer(new SearchProperties(50));

    @Test
    void normalizesCaseWhitespaceAndSeparators() {
        assertThat(normalizer.normalize("  Address-Proof  ").value()).isEqualTo("address proof");
        assertThat(normalizer.normalize("Proof___of / Residency").value()).isEqualTo("proof of residency");
    }

    @Test
    void rejectsBlankPunctuationOnlyAndLongQueries() {
        assertThatThrownBy(() -> normalizer.normalize("  "))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> normalizer.normalize("..."))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> normalizer.normalize("a".repeat(51)))
                .isInstanceOf(InvalidRequestException.class);
    }
}
