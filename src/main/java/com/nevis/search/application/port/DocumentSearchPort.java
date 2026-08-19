package com.nevis.search.application.port;

import com.nevis.search.domain.DocumentSearchResult;

import java.util.List;
import java.util.Set;

public interface DocumentSearchPort {

    List<DocumentSearchResult> search(Set<String> terms, int limit);
}
