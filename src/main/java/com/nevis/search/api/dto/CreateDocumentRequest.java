package com.nevis.search.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDocumentRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank String content
) {
}

