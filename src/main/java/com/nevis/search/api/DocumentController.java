package com.nevis.search.api;

import com.nevis.search.api.dto.ApiError;
import com.nevis.search.api.dto.CreateDocumentRequest;
import com.nevis.search.api.dto.DocumentResponse;
import com.nevis.search.application.DocumentService;
import com.nevis.search.domain.Document;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/clients/{clientId}/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    @Operation(summary = "Create a text document for a client")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Document created",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = DocumentResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Client not found",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported media type",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<DocumentResponse> create(
            @PathVariable UUID clientId,
            @Valid @RequestBody CreateDocumentRequest request
    ) {
        Document document = documentService.create(clientId, request.title(), request.content());
        return ResponseEntity
                .created(URI.create("/clients/" + clientId + "/documents/" + document.id()))
                .body(DocumentResponse.from(document));
    }

}
