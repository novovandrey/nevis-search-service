package com.nevis.search.application.observability;

import com.nevis.search.config.DocumentProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class DocumentMetrics {

    private final Counter createRequests;
    private final Timer createLatency;
    private final Timer embeddingLatency;
    private final DistributionSummary sizeBytes;
    private final DistributionSummary chunksCreated;

    public DocumentMetrics(MeterRegistry registry, DocumentProperties documentProperties) {
        createRequests = Counter.builder("document.create.requests")
                .description("Document creation attempts reaching the application use case")
                .register(registry);
        createLatency = timer(registry, "document.create.latency", "Complete document creation latency");
        embeddingLatency = timer(registry, "document.embedding.latency", "Total chunk embedding latency per document creation");
        sizeBytes = summary(
                registry,
                "document.size.bytes",
                "Document content size in UTF-8 bytes",
                (double) documentProperties.maxContentLength() * 4
        );
        chunksCreated = summary(
                registry,
                "document.chunks.created",
                "Chunks generated per document",
                documentProperties.maxContentLength()
        );
    }

    public <T> T recordCreate(Supplier<T> operation) {
        createRequests.increment();
        return createLatency.record(operation);
    }

    public <T> T recordEmbedding(Supplier<T> operation) {
        return embeddingLatency.record(operation);
    }

    public void recordSizeBytes(int bytes) {
        sizeBytes.record(bytes);
    }

    public void recordChunksCreated(int count) {
        chunksCreated.record(count);
    }

    private Timer timer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }

    private DistributionSummary summary(
            MeterRegistry registry,
            String name,
            String description,
            double maximumExpectedValue
    ) {
        return DistributionSummary.builder(name)
                .description(description)
                .maximumExpectedValue(maximumExpectedValue)
                .publishPercentileHistogram()
                .register(registry);
    }
}
