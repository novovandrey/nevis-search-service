package com.nevis.search.application.port;

import java.util.Set;

public interface QueryExpansionPort {

    Set<String> expand(String normalizedQuery);
}

