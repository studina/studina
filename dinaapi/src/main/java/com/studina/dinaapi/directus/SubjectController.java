package com.studina.dinaapi.directus;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/subjects")
public class SubjectController {

	private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
			new ParameterizedTypeReference<>() {};

	private final RestClient http = RestClient.create();
	private final DirectusAuthorizationService authorizationService;

	@Value("${directus.url}")
	private String directusUrl;

	@Value("${directus.admin-email}")
	private String adminEmail;

	@Value("${directus.admin-password}")
	private String adminPassword;

	public SubjectController(DirectusAuthorizationService authorizationService) {
		this.authorizationService = authorizationService;
	}

	// ── POST /subjects ────────────────────────────────────────────────────
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> createSubject(
			@RequestBody Map<String, String> request,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
					String authorizationHeader) {
		String name = request.get("name");
		if (name == null || name.isBlank()) {
			return ResponseEntity.badRequest()
					.body(Map.of("error", "El nombre de la materia es obligatorio"));
		}

		String userId = authorizationService.requireUserId(authorizationHeader);
		String adminToken = authorizationService.getAdminToken();

		Map<String, Object> response = http.post()
				.uri(directusUrl + "/items/subjects")
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.body(Map.of("name", name.trim(), "user", userId))
				.retrieve()
				.body(MAP_TYPE);

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(dataMap(response));
	}

	// TODO: listar únicamente las materias del usuario autenticado.
	@GetMapping
	public ResponseEntity<?> listSubjects(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
					String authorizationHeader) {
		String userId = authorizationService.requireUserId(authorizationHeader);
		String adminToken = authorizationService.getAdminToken();

		Map<String, Object> response = http.get()
				.uri(directusUrl + "/items/subjects?filter[user][_eq]=" + userId
						+ "&fields=id,name&sort=name")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.retrieve()
				.body(MAP_TYPE);

		return ResponseEntity.ok(response != null ? response.get("data") : null);
	}

	// TODO: validar propiedad antes de renombrar la materia.
	@PatchMapping("/{subjectId}")
	public ResponseEntity<?> updateSubject(@PathVariable String subjectId) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	// TODO: validar propiedad antes de eliminar o archivar la materia.
	@DeleteMapping("/{subjectId}")
	public ResponseEntity<?> deleteSubject(@PathVariable String subjectId) {
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> dataMap(Map<String, Object> response) {
		if (response == null || !(response.get("data") instanceof Map<?, ?>)) {
			throw new IllegalStateException(
					"Directus devolvió una respuesta inválida"
			);
		}

		return (Map<String, Object>) response.get("data");
	}
}
