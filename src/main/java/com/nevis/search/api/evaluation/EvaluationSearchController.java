package com.nevis.search.api.evaluation;

import com.nevis.search.application.evaluation.SearchEvaluationResult;
import com.nevis.search.application.evaluation.SearchEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("evaluation")
@RestController
@RequestMapping("/internal/evaluation")
public class EvaluationSearchController {

    private final SearchEvaluationService searchEvaluationService;

    public EvaluationSearchController(SearchEvaluationService searchEvaluationService) {
        this.searchEvaluationService = searchEvaluationService;
    }

    @PostMapping("/search")
    @Operation(summary = "Execute an internal diagnostic document-search evaluation")
    public EvaluationSearchResponse search(@Valid @RequestBody EvaluationSearchRequest request) {
        SearchEvaluationResult result = searchEvaluationService.evaluate(
                request.query(), request.mode(), request.overrides()
        );
        return EvaluationSearchResponse.from(result);
    }
}
