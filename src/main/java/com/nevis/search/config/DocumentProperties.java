package com.nevis.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nevis.documents")
public record DocumentProperties(int maxContentLength) {

    public DocumentProperties {
        if (maxContentLength < 1) {
            throw new IllegalArgumentException("Invalid nevis.documents configuration");
        }
    }
}

