package com.nevis.search.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateDocumentRequest(
        @NotBlank @Size(max = 255) @Schema(minLength = 1, maxLength = 255) String title,
        @NotBlank String content
) {
}
