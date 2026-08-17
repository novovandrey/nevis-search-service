package com.nevis.search.api.dto;

import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        discriminatorProperty = "type",
        oneOf = {ClientSearchResponse.class, DocumentSearchResponse.class},
        discriminatorMapping = {
                @DiscriminatorMapping(value = "CLIENT", schema = ClientSearchResponse.class),
                @DiscriminatorMapping(value = "DOCUMENT", schema = DocumentSearchResponse.class)
        }
)
public sealed interface SearchResultResponse permits ClientSearchResponse, DocumentSearchResponse {

    SearchResultType type();
}

