package com.nevis.search.domain;

public record ClientSearchResult(Client client, MatchType matchType) {

    public enum MatchType {
        EXACT,
        FUZZY
    }
}
