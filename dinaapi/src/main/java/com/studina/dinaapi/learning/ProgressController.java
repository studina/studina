package com.studina.dinaapi.learning;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

/**
 * La vista de progreso de una materia entera.
 *
 * <p>Va en su propia ruta y no en {@code /flashcards/progress}, que sigue sirviendo a la
 * pantalla de cierre de un set: ésa filtra por los topics del set y promedia lo que recibe,
 * así que devolverle topics sin practicar le rompería el promedio.
 */
@RestController
@RequestMapping("/progress")
public class ProgressController {

    private final DirectusAuthorizationService authorizationService;
    private final LearningAnalyticsService analyticsService;

    public ProgressController(
            DirectusAuthorizationService authorizationService,
            LearningAnalyticsService analyticsService) {
        this.authorizationService = authorizationService;
        this.analyticsService = analyticsService;
    }

    @GetMapping
    public ResponseEntity<?> subjectOverview(
            @RequestParam String subjectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, subjectId);
        return ResponseEntity.ok(analyticsService.subjectOverview(userId, subjectId));
    }
}
