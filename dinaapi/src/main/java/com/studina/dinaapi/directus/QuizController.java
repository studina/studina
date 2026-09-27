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

import com.studina.dinaapi.ai.rag.QuizQuestion;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.learning.QuizAttemptRequest;
import com.studina.dinaapi.learning.QuizProgressService;
import com.studina.dinaapi.learning.LearningAnalyticsService;

@RestController
@RequestMapping("/quizzes")
public class QuizController {

    private static final Logger logger = LoggerFactory.getLogger(QuizController.class);

    private final DirectusAuthorizationService authorizationService;
    private final QuizSetService quizSetService;
    private final QuizProgressService quizProgressService;
    private final LearningAnalyticsService analyticsService;

    public QuizController(
            DirectusAuthorizationService authorizationService,
            QuizSetService quizSetService,
            QuizProgressService quizProgressService,
            LearningAnalyticsService analyticsService) {
        this.authorizationService = authorizationService;
        this.quizSetService = quizSetService;
        this.quizProgressService = quizProgressService;
        this.analyticsService = analyticsService;
    }

    @PostMapping("/{quizId}/attempts")
    public ResponseEntity<?> recordAnswer(
            @PathVariable String quizId,
            @RequestParam String subjectId,
            @RequestBody QuizAttemptRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        try {
            QuizQuestion question = quizSetService.findQuestion(quizId, subjectId, request.questionId());
            return ResponseEntity.status(201).body(quizProgressService.recordAnswer(
                    userId, subjectId, quizId, question, request));
        } catch (IllegalArgumentException exception) {
            logger.warn("No se pudo guardar respuesta de quiz: quizId={}, questionId={}, reason={}",
                    quizId, request == null ? null : request.questionId(), exception.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            logger.error("Error guardando respuesta de quiz: quizId={}, questionId={}",
                    quizId, request == null ? null : request.questionId(), exception);
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
            return ResponseEntity.ok(quizSetService.listForSubject(subjectId));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudieron cargar los cuestionarios"));
        }
    }

    @PatchMapping("/{quizId}")
    public ResponseEntity<?> rename(
            @PathVariable String quizId,
            @RequestParam String subjectId,
            @RequestBody Map<String, String> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String title = request.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El nombre del cuestionario es obligatorio"));
        }
        if (title.trim().length() > 160) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El nombre del cuestionario no puede superar los 160 caracteres"));
        }

        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        try {
            return ResponseEntity.ok(quizSetService.rename(quizId, subjectId, title.trim()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo actualizar el nombre del cuestionario"));
        }
    }

    @DeleteMapping("/{quizId}")
    public ResponseEntity<?> delete(
            @PathVariable String quizId,
            @RequestParam String subjectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                    String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        try {
            quizSetService.delete(quizId, subjectId);
            analyticsService.recalculateTopicProgressForSubject(subjectId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(403).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo eliminar el cuestionario"));
        }
    }
}