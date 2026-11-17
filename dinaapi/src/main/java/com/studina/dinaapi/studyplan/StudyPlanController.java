package com.studina.dinaapi.studyplan;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/study-plans")
public class StudyPlanController {

    private final DirectusAuthorizationService authorizationService;
    private final GeminiStudyPlanGenerator generator;
    private final StudyPlanPersistenceService persistenceService;

        @Autowired
        public StudyPlanController(
            DirectusAuthorizationService authorizationService,
            GeminiStudyPlanGenerator generator,
            StudyPlanPersistenceService persistenceService
    ) {
        this.authorizationService = authorizationService;
        this.generator = generator;
        this.persistenceService = persistenceService;
    }

    public StudyPlanController(
            DirectusAuthorizationService authorizationService,
            GeminiStudyPlanGenerator generator
    ) {
        this(
                authorizationService,
                generator,
                null
        );
    }

    @PostMapping("/{subjectId}/generate")
    public ResponseEntity<?> generateAndPersist(
            @PathVariable String subjectId,
            @RequestBody GenerateAiStudyPlanRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        String userId
                = authorizationService.requireUserId(
                        authorizationHeader
                );
        authorizationService.requireSubjectOwnership(
                userId,
                subjectId
        );

        try {

            GeneratedStudyPlan result
                    = generator.generate(
                            request
                    );

            if (!result.canBeScheduled()) {
                return ResponseEntity
                        .unprocessableContent()
                        .body(result);
            }

            persistenceService.save(
                    userId,
                    subjectId,
                    request,
                    result
            );

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException exception) {

            return ResponseEntity.badRequest()
                    .body(Map.of("error", exception.getMessage()));

        } catch (IllegalStateException exception) {

            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", exception.getMessage()));

        } catch (Exception exception) {

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "No se pudo guardar el plan de estudio."));
        }
    }

    @GetMapping("/{subjectId}")
    public ResponseEntity<?> getLatest(
            @PathVariable String subjectId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        String userId
                = authorizationService.requireUserId(
                        authorizationHeader
                );
        authorizationService.requireSubjectOwnership(
                userId,
                subjectId
        );

        Map<String, Object> latest
                = persistenceService.findLatest(
                        userId,
                        subjectId
                );

        if (latest == null) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(latest);
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @RequestBody GenerateAiStudyPlanRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
    ) {
        authorizationService.requireUserId(authorizationHeader);

        try {

            GeneratedStudyPlan result
                    = generator.generate(
                            request
                    );

            if (!result.canBeScheduled()) {

                return ResponseEntity
                        .unprocessableContent()
                        .body(
                                result
                        );
            }

            return ResponseEntity.ok(
                    result
            );

        } catch (IllegalArgumentException exception) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    exception.getMessage()
                            )
                    );

        } catch (IllegalStateException exception) {

            return ResponseEntity
                    .status(
                            HttpStatus.BAD_GATEWAY
                    )
                    .body(
                            Map.of(
                                    "error",
                                    exception.getMessage()
                            )
                    );

        } catch (Exception exception) {

            return ResponseEntity
                    .status(
                            HttpStatus.INTERNAL_SERVER_ERROR
                    )
                    .body(
                            Map.of(
                                    "error",
                                    "No se pudo generar el plan de estudio."
                            )
                    );
        }
    }
}
