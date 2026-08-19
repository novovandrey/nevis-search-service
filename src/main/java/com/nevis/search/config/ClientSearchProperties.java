package com.nevis.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nevis.search.client")
public record ClientSearchProperties(double trigramThreshold) {

    public ClientSearchProperties {
        if (trigramThreshold <= 0.0 || trigramThreshold > 1.0) {
            throw new IllegalArgumentException("nevis.search.client.trigram-threshold must be in (0, 1]");
        }
    }
}
