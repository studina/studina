package com.studina.dinaapi.ai.llm;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/ai/models")
public class OllamaModelCatalogController {

    private final DirectusAuthorizationService authorizationService;
    private final OllamaModelCatalogService catalogService;

    public OllamaModelCatalogController(
            DirectusAuthorizationService authorizationService,
            OllamaModelCatalogService catalogService
    ) {
        this.authorizationService = authorizationService;
        this.catalogService = catalogService;
    }

    @GetMapping("/ollama")
    public ResponseEntity<?> installedModels(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
            String authorizationHeader
    ) {
        authorizationService.requireUserId(authorizationHeader);
        try {
            return ResponseEntity.ok(Map.of(
                    "provider", "ollama",
                    "models", catalogService.installedModels()));
        } catch (RestClientException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "No se pudo consultar Ollama. Pedile al responsable de Studina "
                            + "que revise el servidor y volvé a actualizar la lista."));
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "error", "Ollama devolvió una lista de modelos inválida. "
                            + "Pedile al responsable de Studina que revise el servidor."));
        }
    }
}
