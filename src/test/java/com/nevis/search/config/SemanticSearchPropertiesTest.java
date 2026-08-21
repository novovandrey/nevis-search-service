package com.nevis.search.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticSearchPropertiesTest {

    @Test
    void validatesCandidateAndHnswRelationships() {
        new SemanticSearchProperties(50, 250, 500, 60, 0.30, 1.25, 1.0);

        assertThatThrownBy(() -> new SemanticSearchProperties(50, 49, 500, 60, 0.30, 1.25, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticSearchProperties(50, 250, 249, 60, 0.30, 1.25, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
