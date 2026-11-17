package com.studina.dinaapi.studyplan;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
public class StudyPlanPersistenceService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE
            = new ParameterizedTypeReference<>() {
            };

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    public StudyPlanPersistenceService(
            DirectusAuthorizationService authorizationService
    ) {
        this.authorizationService = authorizationService;
    }

    public Map<String, Object> save(
            String userId,
            String subjectId,
            GenerateAiStudyPlanRequest request,
            GeneratedStudyPlan plan
    ) {

        Map<String, Object> payload
                = new LinkedHashMap<>();

        payload.put("user", userId);
        payload.put("subject", subjectId);
        payload.put("milestone_type", request.milestoneType());
        payload.put("milestone_title", request.milestoneTitle());
        payload.put("milestone_description", request.milestoneDescription());
        payload.put("start_date", request.startDate().toString());
        payload.put("exam_date", request.examDate().toString());
        payload.put("review_sessions", request.reviewSessions());
        payload.put("required_minutes", plan.requiredMinutes());
        payload.put("available_minutes", plan.availableMinutes());
        payload.put("missing_minutes", plan.missingMinutes());
        payload.put("selected_topic_ids", request.topics().stream()
                .map(AiStudyTopic::topicId)
                .toList());
        payload.put("availability", request.availability());
        payload.put("sessions", plan.sessions());
        payload.put("date_created", LocalDateTime.now().toString());

        Map<String, Object> response
                = http.post()
                        .uri(directusUrl + "/items/study_plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + authorizationService.getAdminToken()
                        )
                        .body(payload)
                        .retrieve()
                        .body(MAP_TYPE);

        return dataMap(response);
    }

    public Map<String, Object> findLatest(
            String userId,
            String subjectId
    ) {

        Map<String, Object> response
                = http.get()
                        .uri(
                                UriComponentsBuilder
                                        .fromUriString(
                                                directusUrl + "/items/study_plans"
                                        )
                                        .queryParam(
                                                "filter[user][_eq]",
                                                userId
                                        )
                                        .queryParam(
                                                "filter[subject][_eq]",
                                                subjectId
                                        )
                                        .queryParam(
                                                "fields",
                                                "id,milestone_type,milestone_title,"
                                                + "milestone_description,start_date,exam_date,"
                                                + "review_sessions,required_minutes,"
                                                + "available_minutes,missing_minutes,"
                                                + "selected_topic_ids,availability,sessions"
                                        )
                                        .queryParam("sort", "-date_created")
                                        .queryParam("limit", "1")
                                        .build()
                                        .encode()
                                        .toUri()
                        )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + authorizationService.getAdminToken()
                        )
                        .retrieve()
                        .body(MAP_TYPE);

        List<Map<String, Object>> rows
                = dataList(response);

        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row
                = rows.get(0);

        Map<String, Object> result
                = new LinkedHashMap<>();

        Map<String, Object> milestone
                = new LinkedHashMap<>();
        milestone.put("type", row.get("milestone_type"));
        milestone.put("title", row.get("milestone_title"));
        milestone.put("description", row.get("milestone_description"));

        Map<String, Object> plan
                = new LinkedHashMap<>();
        plan.put("canBeScheduled", true);
        plan.put("requiredMinutes", row.get("required_minutes"));
        plan.put("availableMinutes", row.get("available_minutes"));
        plan.put("missingMinutes", row.get("missing_minutes"));
        plan.put("sessions", row.get("sessions"));

        result.put("id", row.get("id"));
        result.put("milestone", milestone);
        result.put("startDate", row.get("start_date"));
        result.put("examDate", row.get("exam_date"));
        result.put("reviewSessions", row.get("review_sessions"));
        result.put("selectedTopicIds", row.get("selected_topic_ids"));
        result.put("availability", row.get("availability"));
        result.put("planResult", plan);

        return result;
    }

    private Map<String, Object> dataMap(
            Map<String, Object> response
    ) {
        if (response == null
                || !(response.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException(
                    "Directus devolvió una respuesta inválida para el plan de estudio."
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result
                = (Map<String, Object>) data;
        return result;
    }

    private List<Map<String, Object>> dataList(
            Map<String, Object> response
    ) {
        Object data
                = response == null
                ? null
                : response.get("data");

        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException(
                    "Directus devolvió una lista inválida de planes de estudio."
            );
        }

        List<Map<String, Object>> result
                = new ArrayList<>();

        for (Object row : rows) {
            if (row instanceof Map<?, ?> values) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mapped
                        = (Map<String, Object>) values;
                result.add(mapped);
            }
        }

        return result;
    }
}
