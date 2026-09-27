package com.studina.dinaapi.learning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.studina.dinaapi.ai.rag.Flashcard;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class LearningAnalyticsService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
    private final LearningProgressPolicy policy;

    @Value("${directus.url}")
    private String directusUrl;

    public LearningAnalyticsService(
            DirectusAuthorizationService authorizationService,
            LearningProgressPolicy policy) {
        this.authorizationService = authorizationService;
        this.policy = policy;
    }

    public void recalculateTopicProgress(String userId, String subjectId) {
        String token = authorizationService.getAdminToken();
        List<Map<String, Object>> sets = list(token, "flashcards", Map.of(
                "filter[subject][_eq]", subjectId,
                "fields", "id",
                "limit", "-1"));
        Set<String> setIds = sets.stream().map(row -> text(row.get("id"))).collect(Collectors.toSet());
        List<Map<String, Object>> quizzes = list(token, "quizzes", Map.of(
                "filter[subject][_eq]", subjectId,
                "fields", "id",
                "limit", "-1"));
        Set<String> quizIds = quizzes.stream().map(row -> text(row.get("id"))).collect(Collectors.toSet());
        Map<String, Set<String>> cardsByTopic = new LinkedHashMap<>();
        Map<String, Set<String>> questionsByTopic = new LinkedHashMap<>();

        if (!setIds.isEmpty()) {
            List<Map<String, Object>> links = list(token, "flashcard_card_topics", Map.of(
                    "filter[flashcard_set_id][_in]", String.join(",", setIds),
                    "fields", "flashcard_set_id,card_id,topic_id",
                    "limit", "-1"));
            for (Map<String, Object> link : links) {
                String topicId = relationId(link.get("topic_id"));
                String cardId = text(link.get("card_id"));
                if (topicId != null && cardId != null) {
                    cardsByTopic.computeIfAbsent(topicId, ignored -> new LinkedHashSet<>()).add(cardId);
                }
            }
        }
        if (!quizIds.isEmpty()) {
            List<Map<String, Object>> links = list(token, "quiz_question_topics", Map.of(
                    "filter[quiz_id][_in]", String.join(",", quizIds),
                    "fields", "quiz_id,question_id,topic_id",
                    "limit", "-1"));
            for (Map<String, Object> link : links) {
                String topicId = relationId(link.get("topic_id"));
                String questionId = text(link.get("question_id"));
                if (topicId != null && questionId != null) {
                    questionsByTopic.computeIfAbsent(topicId, ignored -> new LinkedHashSet<>()).add(questionId);
                }
            }
        }
        Set<String> topicIds = new LinkedHashSet<>(cardsByTopic.keySet());
        topicIds.addAll(questionsByTopic.keySet());
        list(token, "topics", Map.of(
            "filter[subject_id][_eq]", subjectId,
            "fields", "id",
            "limit", "-1")).stream()
            .map(row -> text(row.get("id")))
            .filter(topicId -> topicId != null)
            .forEach(topicIds::add);
        List<Map<String, Object>> existingAggregates = list(token, "user_topic_progress", Map.of(
            "filter[subject_id][_eq]", subjectId,
            "fields", "id,user_id,topic_id",
            "limit", "-1"));
        if (topicIds.isEmpty()) {
            existingAggregates.forEach(row -> http.delete()
                .uri(directusUrl + "/items/user_topic_progress/" + row.get("id"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().toBodilessEntity());
            return;
        }

        Set<String> allCardIds = cardsByTopic.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Map<String, Map<String, Object>> progressByCard = Map.of();
        if (!allCardIds.isEmpty()) {
            List<Map<String, Object>> cardProgress = list(token, "user_flashcard_progress", Map.of(
                    "filter[user_id][_eq]", userId,
                    "filter[card_id][_in]", String.join(",", allCardIds),
                    "fields", "card_id,mastery_score,times_seen,last_seen_at",
                    "limit", "-1"));
            progressByCard = cardProgress.stream().collect(Collectors.toMap(
                    row -> text(row.get("card_id")), row -> row, (first, second) -> second));
        }
        Set<String> allQuestionIds = questionsByTopic.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
        Map<String, List<Map<String, Object>>> quizEvidenceByQuestion = new HashMap<>();
        if (!allQuestionIds.isEmpty()) {
            List<Map<String, Object>> evidence = list(token, "learning_evidence", Map.ofEntries(
                    Map.entry("filter[user_id][_eq]", userId),
                    Map.entry("filter[subject_id][_eq]", subjectId),
                    Map.entry("filter[resource_type][_eq]", "QUIZ"),
                    Map.entry("filter[resource_id][_in]", String.join(",", allQuestionIds)),
                    Map.entry("fields", "resource_id,topic_id,rating,created_at"),
                    Map.entry("sort", "created_at"),
                    Map.entry("limit", "-1")));
            for (Map<String, Object> row : evidence) {
                quizEvidenceByQuestion.computeIfAbsent(text(row.get("resource_id")), ignored -> new ArrayList<>())
                        .add(row);
            }
        }

        Map<String, Map<String, Object>> aggregateByTopic = existingAggregates.stream()
            .filter(row -> userId.equals(text(row.get("user_id"))))
                .collect(Collectors.toMap(row -> relationId(row.get("topic_id")), row -> row, (first, second) -> second));
        existingAggregates.stream()
            .filter(row -> !topicIds.contains(relationId(row.get("topic_id"))))
            .forEach(row -> http.delete()
                .uri(directusUrl + "/items/user_topic_progress/" + row.get("id"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().toBodilessEntity());

        for (String topicId : topicIds) {
            TopicProgressSnapshot snapshot = TopicProgressSnapshot.calculate(
                    cardsByTopic.getOrDefault(topicId, Set.of()),
                    progressByCard,
                    questionsByTopic.getOrDefault(topicId, Set.of()),
                    quizEvidenceByQuestion,
                    policy);
            Map<String, Object> aggregate = new HashMap<>();
            aggregate.put("user_id", userId);
            aggregate.put("subject_id", subjectId);
            aggregate.put("topic_id", topicId);
            aggregate.put("mastery_score", snapshot.masteryScore());
            aggregate.put("coverage", snapshot.coverage());
            aggregate.put("unique_resources_seen", snapshot.uniqueResourcesSeen());
            aggregate.put("total_resources", snapshot.totalResources());
            aggregate.put("total_attempts", snapshot.totalAttempts());
            aggregate.put("last_seen_at", snapshot.lastSeenAt());
            Map<String, Object> existing = aggregateByTopic.get(topicId);
            if (existing == null) {
                create(token, "user_topic_progress", aggregate);
            } else {
                http.patch().uri(directusUrl + "/items/user_topic_progress/" + existing.get("id"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .body(aggregate).retrieve().toBodilessEntity();
            }
        }
    }

    public void recalculateTopicProgressForSubject(String subjectId) {
        String token = authorizationService.getAdminToken();
        Set<String> userIds = new LinkedHashSet<>();
        for (String collection : List.of("flashcard_attempts", "quiz_attempts")) {
            list(token, collection, Map.of(
                    "filter[subject_id][_eq]", subjectId,
                    "fields", "user_id",
                    "limit", "-1")).stream()
                    .map(row -> relationId(row.get("user_id")))
                    .filter(userId -> userId != null)
                    .forEach(userIds::add);
        }
        list(token, "user_topic_progress", Map.of(
                "filter[subject_id][_eq]", subjectId,
                "fields", "user_id",
                "limit", "-1")).stream()
                .map(row -> relationId(row.get("user_id")))
                .filter(userId -> userId != null)
                .forEach(userIds::add);
        userIds.forEach(userId -> recalculateTopicProgress(userId, subjectId));
    }

    public List<Map<String, Object>> topicProgress(String userId, String subjectId) {
        String token = authorizationService.getAdminToken();
        List<Map<String, Object>> aggregates = list(token, "user_topic_progress", Map.of(
                "filter[user_id][_eq]", userId,
                "filter[subject_id][_eq]", subjectId,
                "fields", "id,topic_id,mastery_score,coverage,unique_resources_seen,total_resources,total_attempts,last_seen_at",
                "limit", "-1"));
        Map<String, String> names = list(token, "topics", Map.of(
                "filter[subject_id][_eq]", subjectId,
                "fields", "id,name",
                "limit", "-1")).stream().collect(Collectors.toMap(
                        row -> text(row.get("id")), row -> text(row.get("name")), (first, second) -> first));
        return aggregates.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(row);
            String topicId = relationId(row.get("topic_id"));
            result.put("topic_id", topicId);
            result.put("topic_name", names.get(topicId));
            return result;
        }).toList();
    }

    public List<Map<String, Object>> cardAttempts(String userId, String subjectId, String cardId) {
        return list(authorizationService.getAdminToken(), "flashcard_attempts", Map.ofEntries(
                Map.entry("filter[user_id][_eq]", userId),
                Map.entry("filter[subject_id][_eq]", subjectId),
                Map.entry("filter[card_id][_eq]", cardId),
                Map.entry("fields", "id,flashcard_set_id,card_id,session_id,rating,created_at"),
                Map.entry("sort", "-created_at"),
                Map.entry("limit", "-1")));
    }

    public Map<String, Object> nextReviewCard(
            String userId,
            String subjectId,
            String setId,
            List<String> recentlyShownCardIds) {
        String token = authorizationService.getAdminToken();
        Map<String, Object> set = data(http.get().uri(directusUrl + "/items/flashcards/" + setId + "?fields=id,subject,cards")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(MAP_TYPE));
        if (!subjectId.equals(relationId(set.get("subject")))) {
            throw new IllegalArgumentException("El set no pertenece a esta materia");
        }
        Object rawCards = set.get("cards");
        if (!(rawCards instanceof List<?> cardRows)) {
            throw new IllegalStateException("El set no contiene tarjetas válidas");
        }
        List<Flashcard> cards = cardRows.stream().map(row -> new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(row, Flashcard.class)).toList();
        if (cards.isEmpty()) {
            return Map.of();
        }
        Set<String> ids = cards.stream().map(Flashcard::id).collect(Collectors.toSet());
        List<Map<String, Object>> progress = list(token, "user_flashcard_progress", Map.of(
                "filter[user_id][_eq]", userId,
                "filter[card_id][_in]", String.join(",", ids),
                "fields", "card_id,mastery_score",
                "limit", "-1"));
        Map<String, Double> masteryByCard = progress.stream().collect(Collectors.toMap(
                row -> text(row.get("card_id")), row -> number(row.get("mastery_score"), 0.0), (first, second) -> second));
        List<ReviewCandidate> candidates = cards.stream()
                .map(card -> new ReviewCandidate(card.id(), masteryByCard.getOrDefault(card.id(), 0.0)))
                .toList();
        ReviewCandidate selected = policy.selectNext(
                candidates,
                recentlyShownCardIds == null ? List.of() : recentlyShownCardIds,
                new Random());
        if (selected == null) {
            return Map.of();
        }
        Flashcard card = cards.stream().filter(candidate -> selected.cardId().equals(candidate.id())).findFirst().orElseThrow();
        return Map.of("card", card, "mastery", selected.mastery());
    }

    private List<Map<String, Object>> list(String token, String collection, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(directusUrl + "/items/" + collection);
        params.forEach(builder::queryParam);
        String uri = builder.build().encode().toUriString();
        Map<String, Object> response = http.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException("Directus devolvió una lista inválida: " + collection);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> values) {
                results.add(castMap(values));
            }
        }
        return results;
    }

    private Map<String, Object> create(String token, String collection, Map<String, Object> values) {
        Map<String, Object> response = http.post().uri(directusUrl + "/items/" + collection)
                .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .body(values).retrieve().body(MAP_TYPE);
        return data(response);
    }

    private Map<String, Object> data(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof Map<?, ?> values)) {
            throw new IllegalStateException("Directus devolvió un elemento inválido");
        }
        return castMap(values);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> values) {
        return (Map<String, Object>) values;
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private String relationId(Object value) {
        if (value instanceof Map<?, ?> relation) {
            return text(relation.get("id"));
        }
        return text(value);
    }

    private double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

}
