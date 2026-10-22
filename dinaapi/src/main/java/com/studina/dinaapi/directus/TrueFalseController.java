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

import com.studina.dinaapi.ai.rag.TrueFalseStatement;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.learning.LearningAnalyticsService;
import com.studina.dinaapi.learning.TrueFalseAttemptRequest;
import com.studina.dinaapi.learning.TrueFalseProgressService;

@RestController
@RequestMapping("/true-false")
public class TrueFalseController {

    private static final Logger logger = LoggerFactory.getLogger(TrueFalseController.class);

    private final DirectusAuthorizationService authorizationService;
    private final TrueFalseSetService setService;
    private final TrueFalseProgressService progressService;
    private final LearningAnalyticsService analyticsService;

    public TrueFalseController(
            DirectusAuthorizationService authorizationService,
            TrueFalseSetService setService,
            TrueFalseProgressService progressService,
            LearningAnalyticsService analyticsService) {
        this.authorizationService = authorizationService;
        this.setService = setService;
        this.progressService = progressService;
        this.analyticsService = analyticsService;
    }

    @PostMapping("/{setId}/attempts")
    public ResponseEntity<?> recordAnswer(
            @PathVariable String setId,
            @RequestBody TrueFalseAttemptRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        String subjectId = setService.requireOwnedSet(userId, setId);
        try {
            TrueFalseStatement statement = setService.findStatement(setId, request.statementId());
            return ResponseEntity.status(201).body(progressService.recordAnswer(
                    userId, subjectId, setId, statement, request));
        } catch (IllegalArgumentException exception) {
            logger.warn("No se pudo guardar respuesta de verdadero o falso: setId={}, statementId={}, reason={}",
                    setId, request == null ? null : request.statementId(), exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            logger.error("Error guardando respuesta de verdadero o falso: setId={}, statementId={}",
                    setId, request == null ? null : request.statementId(), exception);
            return ResponseEntity.internalServerError().body(Map.of("error", "No se pudo guardar la respuesta"));
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
            return ResponseEntity.ok(setService.listForSubject(subjectId));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudieron cargar los sets de verdadero o falso"));
        }
    }

    @PatchMapping("/{setId}")
    public ResponseEntity<?> rename(
            @PathVariable String setId,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String title = request.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El nombre del set es obligatorio"));
        }
        if (title.trim().length() > 160) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El nombre del set no puede superar los 160 caracteres"));
        }

        String userId = authorizationService.requireUserId(authorizationHeader);
        setService.requireOwnedSet(userId, setId);
        try {
            return ResponseEntity.ok(setService.rename(setId, title.trim()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo actualizar el nombre del set"));
        }
    }

    @DeleteMapping("/{setId}")
    public ResponseEntity<?> delete(
            @PathVariable String setId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        String subjectId = setService.requireOwnedSet(userId, setId);
        try {
            setService.delete(setId);
            analyticsService.recalculateTopicProgressForSubject(subjectId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo eliminar el set de verdadero o falso"));
        }
    }
}
