package com.nevis.search.application.evaluation;

import com.nevis.search.application.embedding.EmbeddingModelCapabilities;
import com.nevis.search.application.port.EvaluationMetadataPort;
import com.nevis.search.config.DocumentChunkingProperties;
import com.nevis.search.config.SemanticSearchProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("evaluation")
public class EvaluationMetadataService {

    private final EvaluationMetadataPort metadataPort;
    private final EmbeddingModelCapabilities capabilities;
    private final DocumentChunkingProperties chunking;
    private final SemanticSearchProperties semantic;

    public EvaluationMetadataService(
            EvaluationMetadataPort metadataPort,
            EmbeddingModelCapabilities capabilities,
            DocumentChunkingProperties chunking,
            SemanticSearchProperties semantic
    ) {
        this.metadataPort = metadataPort;
        this.capabilities = capabilities;
        this.chunking = chunking;
        this.semantic = semantic;
    }

    public EvaluationMetadata metadata() {
        return new EvaluationMetadata(capabilities, chunking, semantic, metadataPort.read());
    }

    public record EvaluationMetadata(
            EmbeddingModelCapabilities model,
            DocumentChunkingProperties chunking,
            SemanticSearchProperties semantic,
            EvaluationDatabaseMetadata database
    ) {
    }
}
