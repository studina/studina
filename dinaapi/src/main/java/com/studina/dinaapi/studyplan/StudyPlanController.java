package com.studina.dinaapi.studyplan;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/study-plans")
public class StudyPlanController {

    private final GeminiStudyPlanGenerator generator;

    public StudyPlanController(
            GeminiStudyPlanGenerator generator
    ) {
        this.generator = generator;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @RequestBody GenerateAiStudyPlanRequest request
    ) {

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
