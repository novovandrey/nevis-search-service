package com.nevis.search.api.evaluation;

import com.nevis.search.application.evaluation.EvaluationMetadataService;
import com.nevis.search.application.evaluation.EvaluationMetadataService.EvaluationMetadata;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("evaluation")
@RestController
@RequestMapping("/internal/evaluation")
public class EvaluationMetadataController {

    private final EvaluationMetadataService metadataService;

    public EvaluationMetadataController(EvaluationMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/metadata")
    @Operation(summary = "Describe the active evaluation model, retrieval configuration and index")
    public EvaluationMetadata metadata() {
        return metadataService.metadata();
    }
}
