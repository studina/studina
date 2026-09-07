package com.studina.dinaapi.directus;

import java.util.Map;
import java.util.List;

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

import com.studina.dinaapi.ai.rag.RagIndexService;
import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/subjects")
public class SubjectController {

	private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
			new ParameterizedTypeReference<>() {};

	private final RestClient http = RestClient.create();
	private final DirectusAuthorizationService authorizationService;
	private final RagIndexService ragIndexService;

	@Value("${directus.url}")
	private String directusUrl;

	@Value("${directus.admin-email}")
	private String adminEmail;

	@Value("${directus.admin-password}")
	private String adminPassword;

	public SubjectController(
			DirectusAuthorizationService authorizationService,
			RagIndexService ragIndexService) {
		this.authorizationService = authorizationService;
		this.ragIndexService = ragIndexService;
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

	// ── PATCH /subjects/{subjectId} ───────────────────────────────────────
	@PatchMapping(value = "/{subjectId}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<?> updateSubject(
			@PathVariable String subjectId,
			@RequestBody Map<String, String> request,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
					String authorizationHeader) {
		String name = request.get("name");
		if (name == null || name.isBlank()) {
			return ResponseEntity.badRequest()
					.body(Map.of("error", "El nombre de la materia es obligatorio"));
		}

		String userId = authorizationService.requireUserId(authorizationHeader);
		authorizationService.requireSubjectOwnership(userId, subjectId);

		Map<String, Object> response = http.patch()
				.uri(directusUrl + "/items/subjects/" + subjectId)
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + authorizationService.getAdminToken())
				.body(Map.of("name", name.trim()))
				.retrieve()
				.body(MAP_TYPE);

		return ResponseEntity.ok(dataMap(response));
	}

	// ── DELETE /subjects/{subjectId} ──────────────────────────────────────
	@DeleteMapping("/{subjectId}")
	public ResponseEntity<Void> deleteSubject(
			@PathVariable String subjectId,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
					String authorizationHeader) {
		String userId = authorizationService.requireUserId(authorizationHeader);
		authorizationService.requireSubjectOwnership(userId, subjectId);
		String adminToken = authorizationService.getAdminToken();

		List<Map<String, Object>> files = getFilesForSubject(
				subjectId,
				adminToken
		);
		for (Map<String, Object> file : files) {
			Object fileId = file.get("id");
			if (fileId == null) {
				continue;
			}

			http.delete()
					.uri(directusUrl + "/files/" + fileId)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
					.retrieve()
					.toBodilessEntity();
			ragIndexService.deleteIndex(fileId.toString());
		}

		deleteSubjectFolder(userId, subjectId, adminToken);

		http.delete()
				.uri(directusUrl + "/items/subjects/" + subjectId)
				.header(HttpHeaders.AUTHORIZATION,
						"Bearer " + adminToken)
				.retrieve()
				.toBodilessEntity();

		return ResponseEntity.noContent().build();
	}

	private List<Map<String, Object>> getFilesForSubject(
			String subjectId,
			String adminToken) {
		Map<String, Object> response = http.get()
				.uri(directusUrl + "/files?filter[subject][_eq]=" + subjectId
						+ "&fields=id&limit=-1")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.retrieve()
				.body(MAP_TYPE);

		Object data = response != null ? response.get("data") : null;
		if (!(data instanceof List<?>)) {
			throw new IllegalStateException(
					"Directus devolvió una lista de archivos inválida"
			);
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> files = (List<Map<String, Object>>) data;
		return files;
	}

	private void deleteSubjectFolder(
			String userId,
			String subjectId,
			String adminToken) {
		Map<String, Object> foldersResponse = http.get()
				.uri(directusUrl + "/folders?filter[name][_eq]=" + subjectId
						+ "&filter[parent][name][_eq]=" + userId
						+ "&fields=id&limit=1")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.retrieve()
				.body(MAP_TYPE);

		Object data = foldersResponse != null ? foldersResponse.get("data") : null;
		if (!(data instanceof List<?> folders) || folders.isEmpty()
				|| !(folders.getFirst() instanceof Map<?, ?> folder)) {
			return;
		}

		Object folderId = folder.get("id");
		if (folderId == null) {
			return;
		}

		http.delete()
				.uri(directusUrl + "/folders/" + folderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
				.retrieve()
				.toBodilessEntity();
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
