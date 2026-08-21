package com.nevis.search.infrastructure.embedding;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EmbeddingConfiguration {

    @Bean
    EmbeddingModelCapabilities embeddingModelCapabilities() {
        return new EmbeddingModelCapabilities("all-MiniLM-L6-v2", 384, 510);
    }
}
