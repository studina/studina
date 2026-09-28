package com.studina.dinaapi.directus;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.learning.FlashcardAttemptRequest;
import com.studina.dinaapi.learning.FlashcardProgressService;
import com.studina.dinaapi.learning.LearningAnalyticsService;

@RestController
@RequestMapping("/flashcards")
public class FlashcardController {

    private static final Logger logger = LoggerFactory.getLogger(FlashcardController.class);

    private final DirectusAuthorizationService authorizationService;
    private final FlashcardSetService flashcardSetService;
    private final FlashcardProgressService flashcardProgressService;
    private final LearningAnalyticsService analyticsService;

    public FlashcardController(
            DirectusAuthorizationService authorizationService,
            FlashcardSetService flashcardSetService,
            FlashcardProgressService flashcardProgressService,
            LearningAnalyticsService analyticsService) {
        this.authorizationService = authorizationService;
        this.flashcardSetService = flashcardSetService;
        this.flashcardProgressService = flashcardProgressService;
        this.analyticsService = analyticsService;
    }

    @PostMapping("/{flashcardSetId}/attempts")
    public ResponseEntity<?> recordAttempt(
            @PathVariable String flashcardSetId,
            @RequestParam String subjectId,
            @RequestBody FlashcardAttemptRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        try {
            return ResponseEntity.status(201).body(flashcardProgressService.recordAttempt(
                    userId,
                    subjectId,
                    flashcardSetId,
                    flashcardSetService.findCard(flashcardSetId, subjectId, request.cardId()),
                    request
            ));
        } catch (IllegalArgumentException exception) {
            logger.warn("No se pudo guardar intento de flashcard: setId={}, cardId={}, reason={}",
                    flashcardSetId, request == null ? null : request.cardId(), exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            logger.error("Error guardando intento de flashcard: setId={}, cardId={}",
                    flashcardSetId, request == null ? null : request.cardId(), exception);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo guardar la autoevaluación"
            ));
        }
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
            analyticsService.recalculateTopicProgressForSubject(subjectId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo eliminar el set de flashcards"
            ));
        }
    }

    @GetMapping("/progress")
    public ResponseEntity<?> topicProgress(
            @RequestParam String subjectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        return ResponseEntity.ok(analyticsService.topicProgress(userId, subjectId));
    }

    @GetMapping("/cards/{cardId}/attempts")
    public ResponseEntity<?> cardAttempts(
            @PathVariable String cardId,
            @RequestParam String subjectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        return ResponseEntity.ok(analyticsService.cardAttempts(userId, subjectId, cardId));
    }

    @GetMapping("/{flashcardSetId}/mastery")
    public ResponseEntity<?> cardMastery(
            @PathVariable String flashcardSetId,
            @RequestParam String subjectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        try {
            flashcardSetService.requireSetOwnership(flashcardSetId, subjectId);
            return ResponseEntity.ok(analyticsService.cardMastery(userId, subjectId, flashcardSetId));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }

    @GetMapping("/review/next")
    public ResponseEntity<?> nextReviewCard(
            @RequestParam String subjectId,
            @RequestParam String flashcardSetId,
            @RequestParam(required = false) java.util.List<String> recentlyShownCardIds,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        try {
            flashcardSetService.requireSetOwnership(flashcardSetId, subjectId);
            return ResponseEntity.ok(analyticsService.nextReviewCard(
                    userId, subjectId, flashcardSetId, recentlyShownCardIds));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        }
    }
}