package com.nevis.search.application;

import com.nevis.search.config.SearchProperties;
import com.nevis.search.config.SemanticSearchProperties;
import com.nevis.search.domain.Document;
import com.nevis.search.domain.DocumentSearchResult;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class HybridDocumentSearchMerger {

    private final int rrfK;
    private final double lexicalWeight;
    private final double vectorWeight;
    private final int maxResults;

    public HybridDocumentSearchMerger(SemanticSearchProperties semanticProperties, SearchProperties searchProperties) {
        this.rrfK = semanticProperties.rrfK();
        this.lexicalWeight = semanticProperties.lexicalWeight();
        this.vectorWeight = semanticProperties.vectorWeight();
        this.maxResults = searchProperties.maxResults();
    }

    public List<DocumentSearchResult> merge(
            List<DocumentSearchResult> lexicalResults,
            List<DocumentSearchResult> semanticResults
    ) {
        return merge(lexicalResults, semanticResults, new HybridSearchConfiguration(rrfK, lexicalWeight, vectorWeight));
    }

    public List<DocumentSearchResult> merge(
            List<DocumentSearchResult> lexicalResults,
            List<DocumentSearchResult> semanticResults,
            HybridSearchConfiguration configuration
    ) {
        Map<UUID, MergedDocument> merged = new LinkedHashMap<>();
        addRankContributions(merged, lexicalResults, configuration.lexicalWeight(), configuration.rrfK());
        addRankContributions(merged, semanticResults, configuration.vectorWeight(), configuration.rrfK());

        return merged.values().stream()
                .sorted(Comparator
                        .comparingDouble(MergedDocument::score).reversed()
                        .thenComparing(result -> result.document().createdAt(), Comparator.reverseOrder())
                        .thenComparing(result -> result.document().id()))
                .limit(maxResults)
                .map(result -> new DocumentSearchResult(result.document(), result.score()))
                .toList();
    }

    private void addRankContributions(
            Map<UUID, MergedDocument> merged,
            List<DocumentSearchResult> rankedResults,
            double weight,
            int rrfK
    ) {
        for (int index = 0; index < rankedResults.size(); index++) {
            Document document = rankedResults.get(index).document();
            double contribution = weight / (rrfK + index + 1);
            merged.merge(document.id(), new MergedDocument(document, contribution),
                    (current, ignored) -> new MergedDocument(current.document(), current.score() + contribution));
        }
    }

    private record MergedDocument(Document document, double score) {
    }

    public record HybridSearchConfiguration(int rrfK, double lexicalWeight, double vectorWeight) {

        public HybridSearchConfiguration {
            if (rrfK < 1 || !Double.isFinite(lexicalWeight) || lexicalWeight <= 0
                    || !Double.isFinite(vectorWeight) || vectorWeight <= 0) {
                throw new IllegalArgumentException("Invalid hybrid search configuration");
            }
        }
    }
}
