package com.studina.dinaapi.learning;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.ai.llm.LlmRequest;
import com.studina.dinaapi.ai.llm.LlmUseCase;
import com.studina.dinaapi.ai.rag.AiRetry;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@Service
public class TopicCatalogService {

    private static final Logger logger = LoggerFactory.getLogger(TopicCatalogService.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient http = RestClient.create();
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DirectusAuthorizationService authorizationService;

    @Value("${directus.url}")
    private String directusUrl;

    @Value("${ai.classification.max-attempts:3}")
    private int classificationMaxAttempts;

    /** Base del backoff exponencial: espera 1s y después 2s antes del último intento. */
    @Value("${ai.classification.retry-delay-ms:1000}")
    private long classificationRetryDelayMs;

    public TopicCatalogService(
            DirectusAuthorizationService authorizationService,
            LlmClient llmClient) {
        this.authorizationService = authorizationService;
        this.llmClient = llmClient;
    }

    public List<Topic> listForSubject(String subjectId) {
        Map<String, Object> response = http.get()
                .uri(directusUrl + "/items/topics?filter[subject_id][_eq]=" + subjectId
                        + "&fields=id,subject_id,name,description&sort=name&limit=-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationService.getAdminToken())
                .retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException("Directus devolvió topics inválidos");
        }
        return rows.stream().map(this::toTopic).toList();
    }

    public List<Topic> listForFilesOrSubjectFallback(String subjectId, List<String> fileIds) {
        Set<String> fileTopicIds = listTopicIdsForFiles(fileIds);
        List<Topic> subjectTopics = listForSubject(subjectId);
        if (fileTopicIds.isEmpty()) {
            logger.warn("Los archivos seleccionados no tienen topics asociados; se usarán los topics de la materia {}",
                    subjectId);
        }
        return selectTopicsForGeneration(subjectTopics, fileTopicIds);
    }

    static List<Topic> selectTopicsForGeneration(List<Topic> subjectTopics, Set<String> fileTopicIds) {
        if (fileTopicIds.isEmpty()) {
            return subjectTopics;
        }
        return subjectTopics.stream()
                .filter(topic -> fileTopicIds.contains(String.valueOf(topic.id())))
                .toList();
    }

    private Set<String> listTopicIdsForFiles(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Set.of();
        }
        URI url = UriComponentsBuilder.fromUriString(directusUrl)
                .path("/items/file_topics")
                .queryParam("filter[file_id][_in]", String.join(",", new LinkedHashSet<>(fileIds)))
                .queryParam("fields", "topic_id")
                .queryParam("limit", -1)
                .build()
                .encode()
                .toUri();
        Map<String, Object> response = http.get()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationService.getAdminToken())
                .retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof List<?> rows)) {
            throw new IllegalStateException("Directus devolvió asociaciones file-topic inválidas");
        }

        Set<String> topicIds = new LinkedHashSet<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> fields && fields.get("topic_id") != null) {
                Object topic = fields.get("topic_id");
                if (topic instanceof Map<?, ?> relatedTopic) {
                    topic = relatedTopic.get("id");
                }
                if (topic != null) {
                    topicIds.add(topic.toString());
                }
            }
        }
        return topicIds;
    }

    public Topic createOrReuse(String subjectId, String name, String description) {
        String normalizedName = normalize(name);
        Topic existing = listForSubject(subjectId).stream()
                .filter(topic -> normalize(topic.name()).equals(normalizedName))
                .findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }

        Map<String, Object> response = http.post().uri(directusUrl + "/items/topics")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationService.getAdminToken())
                .body(Map.of(
                        "subject_id", subjectId,
                        "name", name.trim(),
                        "normalized_name", normalizedName,
                        "description", description == null ? "" : description.trim()
                ))
                .retrieve().body(MAP_TYPE);
        Object data = response == null ? null : response.get("data");
        if (!(data instanceof Map<?, ?> row)) {
            throw new IllegalStateException("Directus no creó el topic");
        }
        return toTopic(row);
    }

    public void associateFile(String fileId, Topic topic) {
        Map<String, Object> existing = http.get()
                .uri(directusUrl + "/items/file_topics?filter[file_id][_eq]=" + fileId
                        + "&filter[topic_id][_eq]=" + topic.id() + "&fields=id&limit=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationService.getAdminToken())
                .retrieve().body(MAP_TYPE);
        if (existing != null && existing.get("data") instanceof List<?> rows && !rows.isEmpty()) {
            return;
        }
        http.post().uri(directusUrl + "/items/file_topics")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authorizationService.getAdminToken())
                .body(Map.of("file_id", fileId, "topic_id", topic.id()))
                .retrieve().body(MAP_TYPE);
    }

    public List<Topic> detectAndAssociate(String subjectId, String fileId, String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return List.of();
        }

        List<Topic> catalog = listForSubject(subjectId);
        String availableTopicsJson = catalog.stream()
                .map(topic -> Map.of(
                        "id", String.valueOf(topic.id()),
                        "name", topic.name(),
                        "description", topic.description() == null ? "" : topic.description()))
                .map(this::toJson)
                .collect(Collectors.joining(",\n"));

        String response = classifyWithRetry(availableTopicsJson, extractedText);

        try {
            TopicClassification classification = objectMapper.readValue(response, TopicClassification.class);
            return resolveAndAssociate(subjectId, fileId, catalog, classification);
        } catch (Exception exception) {
            throw new IllegalArgumentException("No se pudieron clasificar los temas del documento", exception);
        }
    }

    /**
     * Llama al clasificador reintentando los fallos pasajeros del proveedor.
     *
     * <p>Un 503 de medio segundo dejaba el archivo sin temas para siempre: la subida respondía
     * como exitosa y la única forma de recuperarlo era borrar el archivo y volver a subirlo.
     * Como esto corre dentro de la request de subida, el usuario espera, así que los intentos
     * están acotados y el backoff es corto.
     */
    private String classifyWithRetry(String availableTopicsJson, String extractedText) {
        return AiRetry.withBackoff(
                classificationMaxAttempts,
                classificationRetryDelayMs,
                AiRetry.realSleeper(),
                () -> callClassifier(availableTopicsJson, extractedText),
                (attempt, maxAttempts, delayMs) -> logger.warn(
                        "El clasificador de temas falló por sobrecarga del proveedor "
                                + "(intento {} de {}); reintentando en {} ms",
                        attempt, maxAttempts, delayMs));
    }

    private String callClassifier(String availableTopicsJson, String extractedText) {
        return llmClient.generate(LlmRequest.of(
                """
                        Sos un clasificador de temas de estudio. Analizá el documento y decidí qué topics
                        canónicos de la materia representan sus conceptos centrales.

                        Respondé únicamente JSON válido con esta estructura exacta:
                        {"existing_topic_ids":["id existente"],"new_topics":[{"name":"nombre","description":"descripción"}]}

                        Reglas:
                        - Reutilizá un topic existente cuando represente semánticamente el mismo concepto,
                          aunque el documento use una variante, una subcategoría o una formulación distinta.
                          Por ejemplo, si existe "Consistencia eventual/fuerte", no propongas de nuevo
                          "Consistencia eventual": seleccioná el ID del topic existente.
                        - Elegí los IDs exclusivamente del catálogo recibido; nunca inventes IDs.
                        - Proponé topics nuevos solamente para conceptos centrales que no estén cubiertos
                          por ninguno de los topics existentes. Devolvé entre 0 y 3 topics nuevos.
                        - Podés asociar uno o varios topics existentes y además proponer topics nuevos
                          en la misma respuesta cuando el documento cubra ambos casos.
                        - No crees topics para detalles secundarios, ejemplos aislados ni sinónimos.
                        - Si el catálogo está vacío, proponé entre 1 y 3 topics nuevos.
                        - No devuelvas nombres de topics existentes en "new_topics".
                        """,
                """
                        Topics canónicos disponibles en esta materia:
                        [%s]

                        Contenido del documento:
                        %s
                        """.formatted(availableTopicsJson,
                        extractedText.substring(0, Math.min(extractedText.length(), 12_000)))
        ).forUseCase(LlmUseCase.CLASSIFICATION)).content();
    }

    List<Topic> resolveAndAssociate(
            String subjectId,
            String fileId,
            List<Topic> catalog,
            TopicClassification classification) {
        LinkedHashMap<String, Topic> resolved = new LinkedHashMap<>();
        TopicClassificationResolver.Resolution resolution =
                TopicClassificationResolver.resolve(subjectId, catalog, classification);
        for (Topic existing : resolution.existingTopics()) {
            resolved.putIfAbsent(String.valueOf(existing.id()), existing);
        }

        for (NewTopicProposal proposal : resolution.newTopics()) {
            Topic topic = createOrReuse(subjectId, proposal.name(), proposal.description());
            resolved.putIfAbsent(String.valueOf(topic.id()), topic);
        }

        List<Topic> topics = List.copyOf(resolved.values());
        topics.forEach(topic -> associateFile(fileId, topic));
        return topics;
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar el catálogo de topics", exception);
        }
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El nombre del tema es obligatorio");
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private Topic toTopic(Object value) {
        if (!(value instanceof Map<?, ?> row)) {
            throw new IllegalStateException("Directus devolvió un topic inválido");
        }
        return new Topic(
                row.get("id").toString(),
                String.valueOf(row.get("subject_id")),
                String.valueOf(row.get("name")),
                row.get("description") == null ? "" : row.get("description").toString()
        );
    }
}
