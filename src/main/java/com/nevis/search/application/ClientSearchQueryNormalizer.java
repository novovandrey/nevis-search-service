package com.nevis.search.application;

import com.nevis.search.application.exception.InvalidRequestException;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.domain.ClientSearchQuery;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class ClientSearchQueryNormalizer {

    private final SearchProperties properties;

    public ClientSearchQueryNormalizer(SearchProperties properties) {
        this.properties = properties;
    }

    public ClientSearchQuery normalize(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            throw new InvalidRequestException("Search query must not be blank");
        }

        String normalized = rawQuery
                .strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");

        if (normalized.codePoints().noneMatch(Character::isLetterOrDigit)) {
            throw new InvalidRequestException("Search query must contain searchable text");
        }
        if (normalized.length() > properties.maxQueryLength()) {
            throw new InvalidRequestException(
                    "Search query must not exceed " + properties.maxQueryLength() + " characters"
            );
        }
        return new ClientSearchQuery(normalized);
    }
}
