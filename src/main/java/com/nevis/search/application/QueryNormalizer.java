package com.nevis.search.application;

import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.domain.SearchQuery;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class QueryNormalizer {

    private final SearchProperties properties;

    public QueryNormalizer(SearchProperties properties) {
        this.properties = properties;
    }

    public SearchQuery normalize(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new InvalidRequestException("Search query must not be blank");
        }

        String normalized = rawQuery
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[-_/\\\\]+", " ")
                .replaceAll("\\s+", " ");

        if (normalized.isBlank()) {
            throw new InvalidRequestException("Search query must contain searchable text");
        }
        if (normalized.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new InvalidRequestException("Search query must contain searchable text");
        }
        if (normalized.length() > properties.maxQueryLength()) {
            throw new InvalidRequestException(
                    "Search query must not exceed " + properties.maxQueryLength() + " characters"
            );
        }
        return new SearchQuery(normalized);
    }
}
