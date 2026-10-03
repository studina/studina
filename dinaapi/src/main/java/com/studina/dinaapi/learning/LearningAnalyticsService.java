package com.studina.dinaapi.learning;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import com.studina.dinaapi.directus.FlashcardSetService;

@Service
public class LearningAnalyticsService {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final DirectusAuthorizationService authorizationService;
    private final LearningProgressPolicy policy;
    private final FlashcardSetService flashcardSetService;

    @Value("${directus.url}")
    private String directusUrl;

    public LearningAnalyticsService(
            DirectusAuthorizationService authorizationService,
            LearningProgressPolicy policy,
            FlashcardSetService flashcardSetService) {
        this.authorizationService = authorizationService;
        this.policy = policy;
        this.flashcardSetService = flashcardSetService;
    }

    public void recalculateTopicProgress(String userId, String subjectId) {
        String token = authorizationService.getAdminToken();
        Set<String> topicIds = subjectTopicIds(token, subjectId);

        List<Map<String, Object>> existingAggregates = list(token, "user_topic_progress", Map.of(
                "filter[subject_id][_eq]", subjectId,
                "filter[user_id][_eq]", userId,
                "fields", "id,topic_id",
                "limit", "-1"));

        if (topicIds.isEmpty()) {
            existingAggregates.forEach(row -> deleteAggregate(token, row));
            return;
        }

        Map<String, Set<String>> resourcesByTopic = resourcesByTopic(token, topicIds);
        Map<String, Map<String, Object>> progressByResource = list(token, "user_resource_progress", Map.of(
                        "filter[user_id][_eq]", userId,
                        "filter[subject_id][_eq]", subjectId,
                        "fields", "resource_id,mastery_score,times_seen,last_seen_at",
                        "limit", "-1")).stream()
                .collect(Collectors.toMap(
                        row -> text(row.get("resource_id")), row -> row, (first, second) -> second));

        Map<String, Map<String, Object>> aggregateByTopic = existingAggregates.stream()
                .collect(Collectors.toMap(
                        row -> relationId(row.get("topic_id")), row -> row, (first, second) -> second));
        existingAggregates.stream()
                .filter(row -> !topicIds.contains(relationId(row.get("topic_id"))))
                .forEach(row -> deleteAggregate(token, row));

        for (String topicId : topicIds) {
            upsertTopicProgress(token, userId, subjectId, topicId,
                    resourcesByTopic.getOrDefault(topicId, Set.of()),
                    progressByResource,
                    aggregateByTopic.get(topicId));
        }
    }

    /**
     * Recalcula un solo topic: el del recurso que se acaba de practicar.
     *
     * <p>Calificar una tarjeta cambia el mastery de un recurso, y un recurso pertenece a un
     * único topic, así que reconstruir los demás sería reescribirlos con el valor que ya
     * tenían. Esta versión hace una sola escritura en lugar de una por topic de la materia.
     *
     * <p>A diferencia de la versión completa, no crea filas en cero para los topics que nadie
     * practicó ni borra las de topics que ya no existen: ese mantenimiento corre cuando se
     * borra un set. Que un topic sin practicar no tenga fila es además lo correcto —no hay
     * evidencia no es lo mismo que mastery 0—, la misma regla que usa el armado de la ronda.
     */
    public void recalculateTopicProgress(String userId, String subjectId, String topicId) {
        if (topicId == null || topicId.isBlank()) {
            return;
        }
        String token = authorizationService.getAdminToken();
        Set<String> resourceIds = resourcesByTopic(token, Set.of(topicId))
                .getOrDefault(topicId, Set.of());

        Map<String, Map<String, Object>> progressByResource = resourceIds.isEmpty()
                ? Map.of()
                : list(token, "user_resource_progress", Map.of(
                                "filter[user_id][_eq]", userId,
                                "filter[resource_id][_in]", String.join(",", resourceIds),
                                "fields", "resource_id,mastery_score,times_seen,last_seen_at",
                                "limit", "-1")).stream()
                        .collect(Collectors.toMap(
                                row -> text(row.get("resource_id")), row -> row, (first, second) -> second));

        Map<String, Object> existing = findOneAggregate(token, userId, topicId);
        upsertTopicProgress(token, userId, subjectId, topicId, resourceIds, progressByResource, existing);
    }

    /** Persiste el agregado de un topic. Compartido por las dos rutas para que no se desincronicen. */
    private void upsertTopicProgress(
            String token,
            String userId,
            String subjectId,
            String topicId,
            Set<String> resourceIds,
            Map<String, Map<String, Object>> progressByResource,
            Map<String, Object> existing) {

        TopicProgressSnapshot snapshot = TopicProgressSnapshot.calculate(resourceIds, progressByResource);

        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("user_id", userId);
        aggregate.put("subject_id", subjectId);
        aggregate.put("topic_id", topicId);
        aggregate.put("mastery_score", snapshot.masteryScore());
        aggregate.put("unique_resources_seen", snapshot.uniqueResourcesSeen());
        aggregate.put("total_attempts", snapshot.totalAttempts());
        aggregate.put("last_seen_at", snapshot.lastSeenAt());

        if (existing == null) {
            create(token, "user_topic_progress", aggregate);
        } else {
            http.patch().uri(directusUrl + "/items/user_topic_progress/" + existing.get("id"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .body(aggregate).retrieve().toBodilessEntity();
        }
    }

    private Map<String, Object> findOneAggregate(String token, String userId, String topicId) {
        List<Map<String, Object>> rows = list(token, "user_topic_progress", Map.of(
                "filter[user_id][_eq]", userId,
                "filter[topic_id][_eq]", topicId,
                "fields", "id,topic_id",
                "limit", "1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void recalculateTopicProgressForSubject(String subjectId) {
        String token = authorizationService.getAdminToken();
        Set<String> userIds = new LinkedHashSet<>();
        for (String collection : List.of("flashcard_attempts", "quiz_attempts", "user_topic_progress")) {
            list(token, collection, Map.of(
                    "filter[subject_id][_eq]", subjectId,
                    "fields", "user_id",
                    "limit", "-1")).stream()
                    .map(row -> relationId(row.get("user_id")))
                    .filter(userId -> userId != null)
                    .forEach(userIds::add);
        }
        userIds.forEach(userId -> recalculateTopicProgress(userId, subjectId));
    }

    public List<Map<String, Object>> topicProgress(
            String userId, String subjectId, boolean includeCoverage) {
        String token = authorizationService.getAdminToken();
        List<Map<String, Object>> aggregates = list(token, "user_topic_progress", Map.of(
                "filter[user_id][_eq]", userId,
                "filter[subject_id][_eq]", subjectId,
                "fields", "id,topic_id,mastery_score,unique_resources_seen,total_attempts,last_seen_at",
                "limit", "-1"));

        Map<String, String> names = list(token, "topics", Map.of(
                        "filter[subject_id][_eq]", subjectId,
                        "fields", "id,name",
                        "limit", "-1")).stream()
                .collect(Collectors.toMap(
                        row -> text(row.get("id")), row -> text(row.get("name")), (first, second) -> first));

        // coverage y total_resources son derivados, no guardados: la cantidad de recursos es un
        // dato del topic y no del usuario, así que una copia por usuario quedaría vieja cada vez
        // que se agrega un set. Contarlos obliga a listar todas las tarjetas y preguntas, así que
        // sólo se hace cuando el consumidor los pide.
        Map<String, Set<String>> resourcesByTopic = includeCoverage
                ? resourcesByTopic(token, names.keySet())
                : Map.of();

        return aggregates.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(row);
            String topicId = relationId(row.get("topic_id"));
            result.put("topic_id", topicId);
            result.put("topic_name", names.get(topicId));
            if (includeCoverage) {
                int totalResources = resourcesByTopic.getOrDefault(topicId, Set.of()).size();
                int seen = (int) number(row.get("unique_resources_seen"), 0);
                result.put("total_resources", totalResources);
                result.put("coverage", policy.coverage(seen, totalResources));
            }
            return result;
        }).toList();
    }

    /**
     * El panorama completo de una materia: todos sus topics, practicados o no, con el
     * dominio del usuario, la cobertura y los archivos de donde salió cada tema.
     *
     * <p>A diferencia de {@link #topicProgress}, parte del catálogo de topics y no de
     * {@code user_topic_progress}, así que incluye los temas que nadie practicó. Esos salen
     * con {@code mastery_score} y {@code level} en {@code null}: no hay evidencia no es lo
     * mismo que mastery 0, la misma regla que usa el armado de la ronda. En cambio
     * {@code average_mastery} sí los cuenta, como 0: es el dominio de la materia entera, y
     * un tema sin practicar es un tema que todavía no se domina. Sin nada practicado sale
     * {@code null}, para que la pantalla muestre el estado vacío y no un 0%.
     *
     * <p>Es una pantalla entera en una sola respuesta, y cuesta lo que cuesta: lista todas
     * las tarjetas y preguntas de la materia (igual que {@code coverage=true}) más los
     * archivos asociados. Se pide una vez al entrar, no en un bucle.
     */
    public Map<String, Object> subjectOverview(String userId, String subjectId) {
        String token = authorizationService.getAdminToken();

        List<Map<String, Object>> topicRows = list(token, "topics", Map.of(
                "filter[subject_id][_eq]", subjectId,
                "fields", "id,name,description",
                "sort", "name",
                "limit", "-1"));

        Set<String> topicIds = topicRows.stream()
                .map(row -> text(row.get("id")))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, Map<String, Object>> progressByTopic = list(token, "user_topic_progress", Map.of(
                        "filter[user_id][_eq]", userId,
                        "filter[subject_id][_eq]", subjectId,
                        "fields", "topic_id,mastery_score,unique_resources_seen,total_attempts,last_seen_at",
                        "limit", "-1")).stream()
                .collect(Collectors.toMap(
                        row -> relationId(row.get("topic_id")), row -> row, (first, second) -> second));

        Map<String, Set<String>> resourcesByTopic = resourcesByTopic(token, topicIds);
        Map<String, List<Map<String, Object>>> filesByTopic = filesByTopic(token, topicIds);

        List<Map<String, Object>> topics = new ArrayList<>();
        double masterySum = 0.0;
        int practicedTopics = 0;
        int strongTopics = 0;
        int totalAttempts = 0;
        int resourcesSeen = 0;
        int resourcesTotal = 0;
        String lastSeenAt = null;

        for (Map<String, Object> topicRow : topicRows) {
            String topicId = text(topicRow.get("id"));
            if (topicId == null) {
                continue;
            }
            Map<String, Object> progress = progressByTopic.get(topicId);
            int totalResources = resourcesByTopic.getOrDefault(topicId, Set.of()).size();

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("topic_id", topicId);
            entry.put("topic_name", text(topicRow.get("name")));
            entry.put("description", text(topicRow.get("description")));
            entry.put("total_resources", totalResources);
            entry.put("files", filesByTopic.getOrDefault(topicId, List.of()));

            // El recálculo completo de la materia crea filas en cero para los topics que
            // nadie practicó (sección 5.1), así que la fila sola no prueba nada: lo que
            // distingue "sin empezar" de "flojo" es haber registrado algún intento. Sin esa
            // condición, borrar un set convertía todos los temas vírgenes en 0% de dominio.
            int recordedAttempts = progress == null
                    ? 0
                    : (int) number(progress.get("total_attempts"), 0);

            if (recordedAttempts == 0) {
                entry.put("mastery_score", null);
                entry.put("level", null);
                entry.put("unique_resources_seen", 0);
                entry.put("total_attempts", 0);
                entry.put("coverage", 0.0);
                entry.put("last_seen_at", null);
            } else {
                double mastery = number(progress.get("mastery_score"), 0.0);
                int seen = (int) number(progress.get("unique_resources_seen"), 0);
                int attempts = (int) number(progress.get("total_attempts"), 0);
                String seenAt = text(progress.get("last_seen_at"));
                String level = policy.masteryLevel(mastery);

                entry.put("mastery_score", mastery);
                entry.put("level", level);
                entry.put("unique_resources_seen", seen);
                entry.put("total_attempts", attempts);
                entry.put("coverage", policy.coverage(Math.min(seen, totalResources), totalResources));
                entry.put("last_seen_at", seenAt);

                masterySum += mastery;
                practicedTopics++;
                if ("strong".equals(level)) {
                    strongTopics++;
                }
                totalAttempts += attempts;
                resourcesSeen += Math.min(seen, totalResources);
                lastSeenAt = latest(lastSeenAt, seenAt);
            }
            resourcesTotal += totalResources;
            topics.add(entry);
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("subject_id", subjectId);
        overview.put("average_mastery", practicedTopics == 0 ? null : masterySum / topics.size());
        overview.put("practiced_topics", practicedTopics);
        overview.put("strong_topics", strongTopics);
        overview.put("total_topics", topics.size());
        overview.put("coverage", policy.coverage(resourcesSeen, resourcesTotal));
        overview.put("resources_seen", resourcesSeen);
        overview.put("total_resources", resourcesTotal);
        overview.put("total_attempts", totalAttempts);
        overview.put("last_seen_at", lastSeenAt);
        overview.put("topics", topics);
        return overview;
    }

    /**
     * Los archivos de los que salió cada topic, vía {@code file_topics}.
     *
     * <p>Un archivo borrado puede dejar su fila de asociación atrás, así que sólo se
     * devuelven los que todavía existen en Directus y tienen nombre.
     */
    private Map<String, List<Map<String, Object>>> filesByTopic(String token, Set<String> topicIds) {
        if (topicIds.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> fileIdsByTopic = new LinkedHashMap<>();
        Set<String> allFileIds = new LinkedHashSet<>();
        for (Map<String, Object> row : list(token, "file_topics", Map.of(
                "filter[topic_id][_in]", String.join(",", topicIds),
                "fields", "file_id,topic_id",
                "limit", "-1"))) {
            String topicId = relationId(row.get("topic_id"));
            String fileId = relationId(row.get("file_id"));
            if (topicId == null || fileId == null) {
                continue;
            }
            fileIdsByTopic.computeIfAbsent(topicId, ignored -> new ArrayList<>()).add(fileId);
            allFileIds.add(fileId);
        }
        if (allFileIds.isEmpty()) {
            return Map.of();
        }

        Map<String, String> nameById = new LinkedHashMap<>();
        for (Map<String, Object> file : directusFiles(token, allFileIds)) {
            String id = text(file.get("id"));
            // Misma regla que el listado de documentos: el nombre de descarga y, si viene
            // vacío, el título.
            String name = text(file.get("filename_download"));
            if (name == null || name.isBlank()) {
                name = text(file.get("title"));
            }
            if (id != null && name != null && !name.isBlank()) {
                nameById.put(id, name);
            }
        }

        Map<String, List<Map<String, Object>>> filesByTopic = new LinkedHashMap<>();
        fileIdsByTopic.forEach((topicId, fileIds) -> {
            List<Map<String, Object>> files = fileIds.stream()
                    .filter(nameById::containsKey)
                    .distinct()
                    .map(fileId -> Map.<String, Object>of("id", fileId, "filename", nameById.get(fileId)))
                    .toList();
            if (!files.isEmpty()) {
                filesByTopic.put(topicId, files);
            }
        });
        return filesByTopic;
    }

    /** {@code directus_files} no es una colección de {@code /items}, así que va por su propia ruta. */
    private List<Map<String, Object>> directusFiles(String token, Set<String> fileIds) {
        URI uri = UriComponentsBuilder.fromUriString(directusUrl + "/files")
                .queryParam("filter[id][_in]", String.join(",", fileIds))
                .queryParam("fields", "id,filename_download,title")
                .queryParam("limit", "-1")
                .build().encode().toUri();
        return rows(token, uri, "directus_files");
    }

    /** Las fechas llegan como ISO-8601, así que la comparación de texto ya las ordena. */
    private String latest(String current, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return current;
        }
        return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }

    public List<Map<String, Object>> cardAttempts(String userId, String cardId) {
        // El cardId es un UUID global y el filtro de usuario ya acota el resultado, así que
        // la materia no agrega nada: sale de la tarjeta.
        return list(authorizationService.getAdminToken(), "flashcard_attempts", Map.ofEntries(
                Map.entry("filter[user_id][_eq]", userId),
                Map.entry("filter[card_id][_eq]", cardId),
                Map.entry("fields", "id,flashcard_set_id,card_id,rating,created_at"),
                Map.entry("sort", "-created_at"),
                Map.entry("limit", "-1")));
    }

    /**
     * La ronda de repaso completa, en una sola respuesta: la lista ordenada de tarjetas que la
     * persona va a ver, ya con las repeticiones resueltas. La UI la estudia como cualquier set,
     * sin volver a pedir nada entre tarjeta y tarjeta.
     */
    public Map<String, Object> reviewRound(String userId, String subjectId, String setId) {
        List<Flashcard> cards = flashcardSetService.loadCards(List.of(setId))
                .getOrDefault(setId, List.of());
        if (cards.isEmpty()) {
            return Map.of("cards", List.of());
        }

        Map<String, Flashcard> cardsById = cards.stream()
                .collect(Collectors.toMap(Flashcard::id, card -> card, (first, second) -> first));

        List<Flashcard> round = policy.buildReviewRound(reviewCandidates(userId, cards)).stream()
                .map(candidate -> cardsById.get(candidate.cardId()))
                .filter(Objects::nonNull)
                .toList();

        return Map.of("cards", round);
    }

    private List<ReviewCandidate> reviewCandidates(String userId, List<Flashcard> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }
        String token = authorizationService.getAdminToken();
        Set<String> ids = cards.stream().map(Flashcard::id).collect(Collectors.toSet());
        Map<String, Double> masteryByCard = list(token, "user_resource_progress", Map.of(
                        "filter[user_id][_eq]", userId,
                        "filter[resource_id][_in]", String.join(",", ids),
                        "fields", "resource_id,mastery_score",
                        "limit", "-1")).stream()
                .collect(Collectors.toMap(
                        row -> text(row.get("resource_id")),
                        row -> number(row.get("mastery_score"), 0.0),
                        (first, second) -> second));
        // getOrDefault(..., 0.0) aplanaría "sin historial" contra "mastery 0", que es
        // justo la distinción de la que depende la cantidad de repeticiones.
        return cards.stream()
                .map(card -> new ReviewCandidate(card.id(), masteryByCard.get(card.id())))
                .toList();
    }

    /** Every topic of the subject, so that a topic nobody practiced still gets a row. */
    private Set<String> subjectTopicIds(String token, String subjectId) {
        return list(token, "topics", Map.of(
                        "filter[subject_id][_eq]", subjectId,
                        "fields", "id",
                        "limit", "-1")).stream()
                .map(row -> text(row.get("id")))
                .filter(topicId -> topicId != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Flashcards and quiz questions grouped by topic. Both are resources of the same kind for
     * progress purposes, so they share one map and one id space.
     */
    private Map<String, Set<String>> resourcesByTopic(String token, Set<String> topicIds) {
        Map<String, Set<String>> resourcesByTopic = new LinkedHashMap<>();
        if (topicIds.isEmpty()) {
            return resourcesByTopic;
        }
        String topicFilter = String.join(",", topicIds);

        for (Map<String, Object> card : list(token, "flashcard_cards", Map.of(
                "filter[topic_id][_in]", topicFilter,
                "fields", "id,topic_id",
                "limit", "-1"))) {
            index(resourcesByTopic, relationId(card.get("topic_id")), text(card.get("id")));
        }
        for (Map<String, Object> question : list(token, "quiz_questions", Map.of(
                "filter[topic_id][_in]", topicFilter,
                "fields", "id,topic_id",
                "limit", "-1"))) {
            index(resourcesByTopic, relationId(question.get("topic_id")), text(question.get("id")));
        }
        return resourcesByTopic;
    }

    private void index(Map<String, Set<String>> target, String topicId, String resourceId) {
        if (topicId == null || resourceId == null) {
            return;
        }
        target.computeIfAbsent(topicId, ignored -> new LinkedHashSet<>()).add(resourceId);
    }

    private void deleteAggregate(String token, Map<String, Object> row) {
        http.delete()
                .uri(directusUrl + "/items/user_topic_progress/" + row.get("id"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve().toBodilessEntity();
    }

    private List<Map<String, Object>> list(String token, String collection, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(directusUrl + "/items/" + collection);
        params.forEach(builder::queryParam);
        return rows(token, builder.build().encode().toUri(), collection);
    }

    private List<Map<String, Object>> rows(String token, URI uri, String what) {
        Map<String, Object> response = http.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException("Directus devolvió una lista inválida: " + what);
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
