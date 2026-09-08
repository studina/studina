package com.studina.dinaapi.directus;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/quizzes")
public class QuizController {

    private final DirectusAuthorizationService authorizationService;
    private final QuizSetService quizSetService;

    public QuizController(
            DirectusAuthorizationService authorizationService,
            QuizSetService quizSetService) {
        this.authorizationService = authorizationService;
        this.quizSetService = quizSetService;
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
}