package com.nevis.search.api.evaluation;

import com.nevis.search.application.evaluation.SemanticRetrievalMode;

public record EvaluationSemanticPlanResponse(
        String query,
        SemanticRetrievalMode semanticRetrieval,
        String plan
) {
}
