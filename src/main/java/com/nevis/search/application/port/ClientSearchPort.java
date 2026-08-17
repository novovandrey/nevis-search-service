package com.nevis.search.application.port;

import com.nevis.search.domain.ClientSearchResult;
import com.nevis.search.domain.SearchQuery;

import java.util.List;

public interface ClientSearchPort {

    List<ClientSearchResult> search(SearchQuery query, int limit);
}

