package com.nevis.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nevis.search")
public record SearchProperties(int maxQueryLength, int defaultLimit, int maxLimit) {

    public SearchProperties {
        if (maxQueryLength < 1 || defaultLimit < 1 || maxLimit < defaultLimit) {
            throw new IllegalArgumentException("Invalid nevis.search configuration");
        }
    }
}

