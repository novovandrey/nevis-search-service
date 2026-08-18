package com.nevis.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nevis.search")
public record SearchProperties(int maxQueryLength) {

    public SearchProperties {
        if (maxQueryLength < 1) {
            throw new IllegalArgumentException("Invalid nevis.search configuration");
        }
    }
}
