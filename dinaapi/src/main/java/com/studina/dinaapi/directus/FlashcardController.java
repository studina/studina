package com.studina.dinaapi.directus;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/flashcards")
public class FlashcardController {

    private final DirectusAuthorizationService authorizationService;
    private final FlashcardSetService flashcardSetService;

    public FlashcardController(
            DirectusAuthorizationService authorizationService,
            FlashcardSetService flashcardSetService) {
        this.authorizationService = authorizationService;
        this.flashcardSetService = flashcardSetService;
    }

    @GetMapping
    public ResponseEntity<?> listForSubject(
            @RequestParam String subjectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);

        try {
            return ResponseEntity.ok(flashcardSetService.listForSubject(subjectId));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudieron cargar las flashcards"
            ));
        }
    }

    @PatchMapping("/{flashcardSetId}")
    public ResponseEntity<?> rename(
            @PathVariable String flashcardSetId,
            @RequestParam String subjectId,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String title = request.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El nombre del set es obligatorio"
            ));
        }
        if (title.trim().length() > 160) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El nombre del set no puede superar los 160 caracteres"
            ));
        }

        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);

        try {
            return ResponseEntity.ok(
                    flashcardSetService.rename(flashcardSetId, subjectId, title.trim())
            );
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo actualizar el nombre del set"
            ));
        }
    }

    @DeleteMapping("/{flashcardSetId}")
    public ResponseEntity<?> delete(
            @PathVariable String flashcardSetId,
            @RequestParam String subjectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);

        try {
            flashcardSetService.delete(flashcardSetId, subjectId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo eliminar el set de flashcards"
            ));
        }
    }
}