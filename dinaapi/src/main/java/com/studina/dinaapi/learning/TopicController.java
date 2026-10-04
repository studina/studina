package com.studina.dinaapi.learning;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

/**
 * Los temas que crea el estudiante a mano, desde la vista de progreso.
 *
 * <p>Se guardan igual que los que detecta la IA —un topic más sus filas en {@code file_topics}—,
 * así que el resto de la app no distingue unos de otros.
 */
@RestController
@RequestMapping("/topics")
public class TopicController {

    /** El largo de {@code topics.name} en el esquema de Directus. */
    static final int MAX_NAME_LENGTH = 160;

    private final DirectusAuthorizationService authorizationService;
    private final TopicCatalogService topicCatalogService;

    public TopicController(
            DirectusAuthorizationService authorizationService,
            TopicCatalogService topicCatalogService) {
        this.authorizationService = authorizationService;
        this.topicCatalogService = topicCatalogService;
    }

    public record CreateTopicRequest(
            String subjectId,
            String name,
            String description,
            List<String> fileIds) {
    }

    public record UpdateTopicRequest(String name, String description) {
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody CreateTopicRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String name = request.name() == null ? "" : request.name().trim();
        ResponseEntity<?> invalidName = validateName(name);
        if (invalidName != null) {
            return invalidName;
        }
        if (request.fileIds() == null || request.fileIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Tenés que asociar al menos un documento"
            ));
        }

        String userId = authorizationService.requireUserId(authorizationHeader);
        authorizationService.requireSubjectOwnership(userId, request.subjectId());
        authorizationService.requireFilesInSubject(userId, request.subjectId(), request.fileIds());

        try {
            Topic topic = topicCatalogService.createManual(
                    request.subjectId(), name, request.description(), request.fileIds());

            return ResponseEntity.status(HttpStatus.CREATED).body(toBody(topic));
        } catch (TopicAlreadyExistsException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo crear el tema"
            ));
        }
    }

    /**
     * Renombrar un tema o cambiar su descripción, sea creado a mano o por la IA.
     *
     * <p>Los documentos asociados no se editan a propósito: las tarjetas y preguntas generadas
     * desde un documento quedan con el id del tema, así que sacarle ese documento dejaría
     * recursos que cuentan para su dominio sin el material de donde salieron.
     */
    @PatchMapping("/{topicId}")
    public ResponseEntity<?> update(
            @PathVariable String topicId,
            @RequestBody UpdateTopicRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {
        String name = request.name() == null ? "" : request.name().trim();
        ResponseEntity<?> invalidName = validateName(name);
        if (invalidName != null) {
            return invalidName;
        }

        String userId = authorizationService.requireUserId(authorizationHeader);
        Topic topic = topicCatalogService.findById(topicId);
        // 403 y no 404, igual que con las materias: si no, el endpoint serviría para
        // averiguar qué temas existen.
        if (topic == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "No tenés permiso para editar este tema"
            ));
        }
        authorizationService.requireSubjectOwnership(userId, topic.subjectId());

        try {
            return ResponseEntity.ok(toBody(topicCatalogService.update(topic, name, request.description())));
        } catch (TopicAlreadyExistsException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "No se pudo actualizar el tema"
            ));
        }
    }

    /** {@code null} si el nombre (ya recortado) es válido; si no, la respuesta 400. */
    private static ResponseEntity<?> validateName(String name) {
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El nombre del tema es obligatorio"
            ));
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "El nombre del tema no puede superar los " + MAX_NAME_LENGTH + " caracteres"
            ));
        }
        return null;
    }

    private static Map<String, Object> toBody(Topic topic) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic_id", topic.id());
        body.put("topic_name", topic.name());
        body.put("description", topic.description());
        return body;
    }
}
