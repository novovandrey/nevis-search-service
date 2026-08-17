package com.nevis.search.api;

import com.nevis.search.api.dto.CreateDocumentRequest;
import com.nevis.search.api.dto.DocumentResponse;
import com.nevis.search.application.DocumentService;
import com.nevis.search.config.SearchProperties;
import com.nevis.search.domain.Document;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/clients/{clientId}/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final SearchProperties searchProperties;

    public DocumentController(DocumentService documentService, SearchProperties searchProperties) {
        this.documentService = documentService;
        this.searchProperties = searchProperties;
    }

    @PostMapping
    @Operation(summary = "Create a text document for a client")
    public ResponseEntity<DocumentResponse> create(
            @PathVariable UUID clientId,
            @Valid @RequestBody CreateDocumentRequest request
    ) {
        Document document = documentService.create(clientId, request.title(), request.content());
        return ResponseEntity
                .created(URI.create("/clients/" + clientId + "/documents/" + document.id()))
                .body(DocumentResponse.from(document));
    }

    @GetMapping
    @Operation(summary = "List or search documents belonging to one client")
    public List<DocumentResponse> find(
            @PathVariable UUID clientId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        int effectiveLimit = limit == null ? searchProperties.defaultLimit() : limit;
        if (q == null) {
            return documentService.list(clientId, effectiveLimit, offset).stream()
                    .map(DocumentResponse::from)
                    .toList();
        }
        return documentService.search(clientId, q, effectiveLimit).stream()
                .map(result -> DocumentResponse.from(result.document(), result.relevance()))
                .toList();
    }
}
