package com.nevis.search.application;

import com.nevis.search.domain.SearchQuery;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExpanderTest {

    @Test
    void alwaysIncludesOriginalAndDeduplicatesRelatedTerms() {
        QueryExpander expander = new QueryExpander(query -> new LinkedHashSet<>(Set.of(
                "address proof", "utility bill", "bank statement"
        )));

        assertThat(expander.expand(new SearchQuery("address proof")))
                .containsExactlyInAnyOrder("address proof", "utility bill", "bank statement");
    }

    @Test
    void returnsOnlyOriginalWhenNoMappingExists() {
        QueryExpander expander = new QueryExpander(query -> Set.of());

        assertThat(expander.expand(new SearchQuery("passport"))).containsExactly("passport");
    }
}

