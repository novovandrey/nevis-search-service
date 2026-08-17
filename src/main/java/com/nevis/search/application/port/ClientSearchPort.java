package com.nevis.search.application.port;

import com.nevis.search.domain.ClientSearchResult;
import com.nevis.search.domain.ClientSearchQuery;

import java.util.List;

public interface ClientSearchPort {

    List<ClientSearchResult> search(ClientSearchQuery query);
}
