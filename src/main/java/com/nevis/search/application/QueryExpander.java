package com.nevis.search.application;

import com.nevis.search.application.port.QueryExpansionPort;
import com.nevis.search.domain.SearchQuery;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Set;

@Component
public class QueryExpander {

    private final QueryExpansionPort expansionPort;

    public QueryExpander(QueryExpansionPort expansionPort) {
        this.expansionPort = expansionPort;
    }

    public Set<String> expand(SearchQuery query) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(query.value());
        terms.addAll(expansionPort.expand(query.value()));
        return Collections.unmodifiableSet(terms);
    }
}
