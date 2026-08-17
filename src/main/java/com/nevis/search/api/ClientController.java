package com.nevis.search.api;

import com.nevis.search.api.dto.ApiError;
import com.nevis.search.api.dto.ClientResponse;
import com.nevis.search.api.dto.CreateClientRequest;
import com.nevis.search.application.ClientService;
import com.nevis.search.domain.Client;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/clients")
public class ClientController {

    private final ClientService clientService;

    public ClientController(ClientService clientService) {
        this.clientService = clientService;
    }

    @PostMapping
    @Operation(summary = "Create a client")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Client created",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ClientResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported media type",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ClientResponse> create(@Valid @RequestBody CreateClientRequest request) {
        Client client = clientService.create(
                request.firstName(), request.lastName(), request.email(), request.countryOfResidence()
        );
        return ResponseEntity
                .created(URI.create("/clients/" + client.id()))
                .body(ClientResponse.from(client));
    }
}
