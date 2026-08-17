package com.nevis.search.api;

import com.nevis.search.api.dto.ApiError;
import com.nevis.search.api.dto.ClientSearchResponse;
import com.nevis.search.api.dto.DocumentSearchResponse;
import com.nevis.search.api.dto.SearchResultResponse;
import com.nevis.search.application.SearchService;
import com.nevis.search.config.SearchProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class SearchController {

    private final SearchService searchService;
    private final SearchProperties searchProperties;

    public SearchController(SearchService searchService, SearchProperties searchProperties) {
        this.searchService = searchService;
        this.searchProperties = searchProperties;
    }

    @GetMapping("/search")
    @Operation(summary = "Search clients by company domain and documents globally")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Matching clients and documents returned",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = SearchResultResponse.class)))),
            @ApiResponse(responseCode = "400", description = "Missing or invalid search query",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public List<SearchResultResponse> search(
            @RequestParam String q,
            @RequestParam(required = false) Integer limit
    ) {
        int effectiveLimit = limit == null ? searchProperties.defaultLimit() : limit;
        SearchService.GlobalSearchResults results = searchService.search(q, effectiveLimit);
        List<SearchResultResponse> response = new ArrayList<>(
                results.clients().size() + results.documents().size()
        );
        results.clients().stream()
                .map(result -> ClientSearchResponse.from(result.client()))
                .forEach(response::add);
        results.documents().stream()
                .map(result -> DocumentSearchResponse.from(result.document()))
                .forEach(response::add);
        return List.copyOf(response);
    }
}
