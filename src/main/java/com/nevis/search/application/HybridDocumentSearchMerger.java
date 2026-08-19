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
        Map<UUID, MergedDocument> merged = new LinkedHashMap<>();
        addRankContributions(merged, lexicalResults, lexicalWeight);
        addRankContributions(merged, semanticResults, vectorWeight);

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
            double weight
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
}
