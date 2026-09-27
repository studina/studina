package com.studina.dinaapi.learning;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class StudySessionService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    public StudySessionService(DirectusAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    public Map<String, Object> start(String userId, String subjectId, String flashcardSetId) {
        String token = authorizationService.getAdminToken();
        return create(token, Map.of(
                "user_id", userId,
                "subject_id", subjectId,
                "flashcard_set_id", flashcardSetId,
                "status", "active",
                "started_at", Instant.now().toString()
        ));
    }

    public Map<String, Object> startForQuiz(String userId, String subjectId, String quizId) {
        String token = authorizationService.getAdminToken();
        return create(token, Map.of(
                "user_id", userId,
                "subject_id", subjectId,
                "quiz_id", quizId,
                "status", "active",
                "started_at", Instant.now().toString()
        ));
    }

    public Map<String, Object> complete(String userId, String subjectId, String sessionId) {
        String token = authorizationService.getAdminToken();
        Map<String, Object> session = findSession(token, userId, subjectId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("La sesión de estudio no existe");
        }
        if ("completed".equals(session.get("status"))) {
            return session;
        }
        Map<String, Object> response = http.patch()
                .uri(directusUrl + "/items/study_sessions/" + sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(Map.of("status", "completed", "completed_at", Instant.now().toString()))
                .retrieve().body(MAP_TYPE);
        return dataMap(response);
    }

    public Map<String, Object> abandon(String userId, String subjectId, String sessionId) {
        String token = authorizationService.getAdminToken();
        Map<String, Object> session = findSession(token, userId, subjectId, sessionId);
        if (session == null) {
            throw new IllegalArgumentException("La sesión de estudio no existe");
        }
        if (!"active".equals(session.get("status"))) {
            return session;
        }
        Map<String, Object> response = http.patch()
                .uri(directusUrl + "/items/study_sessions/" + sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(Map.of("status", "abandoned", "completed_at", Instant.now().toString()))
                .retrieve().body(MAP_TYPE);
        return dataMap(response);
    }

    public Map<String, Object> requireActive(
            String userId,
            String subjectId,
            String flashcardSetId,
            String sessionId) {
        Map<String, Object> session = findSession(
                authorizationService.getAdminToken(), userId, subjectId, sessionId);
        if (session == null
                || !flashcardSetId.equals(String.valueOf(session.get("flashcard_set_id")))
                || !"active".equals(session.get("status"))) {
            throw new IllegalArgumentException("La sesión no está activa o no corresponde a este set");
        }
        return session;
    }

    public Map<String, Object> requireActiveQuiz(
            String userId,
            String subjectId,
            String quizId,
            String sessionId) {
        Map<String, Object> session = findSession(
                authorizationService.getAdminToken(), userId, subjectId, sessionId);
        if (session == null
                || !quizId.equals(String.valueOf(session.get("quiz_id")))
                || !"active".equals(session.get("status"))) {
            throw new IllegalArgumentException("La sesión no está activa o no corresponde a este cuestionario");
        }
        return session;
    }

    private Map<String, Object> findSession(String token, String userId, String subjectId, String sessionId) {
        String uri = UriComponentsBuilder.fromUriString(directusUrl + "/items/study_sessions")
                .queryParam("filter[id][_eq]", sessionId)
                .queryParam("filter[user_id][_eq]", userId)
                .queryParam("filter[subject_id][_eq]", subjectId)
                .queryParam("fields", "id,user_id,subject_id,flashcard_set_id,quiz_id,status,started_at,completed_at")
                .queryParam("limit", 1)
                .build().encode().toUriString();
        Map<String, Object> response = http.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows) || rows.isEmpty() || !(rows.getFirst() instanceof Map<?, ?> row)) {
            return null;
        }
        return castMap(row);
    }

    private Map<String, Object> create(String token, Map<String, Object> values) {
        Map<String, Object> response = http.post().uri(directusUrl + "/items/study_sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(values).retrieve().body(MAP_TYPE);
        return dataMap(response);
    }

    private Map<String, Object> dataMap(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof Map<?, ?> row)) {
            throw new IllegalStateException("Directus devolvió una sesión inválida");
        }
        return castMap(row);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> row) {
        return (Map<String, Object>) row;
    }
}
