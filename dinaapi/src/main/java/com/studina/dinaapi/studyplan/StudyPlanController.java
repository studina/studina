package com.studina.dinaapi.studyplan;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/study-plans")
public class StudyPlanController {

    private final StudyScheduleGenerator generator;

    public StudyPlanController(StudyScheduleGenerator generator) {
        this.generator = generator;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(
            @RequestBody GenerateStudyPlanRequest request) {

        try {
            GeneratedStudyPlan result =
                    generator.generate(request);

            if (!result.canBeScheduled()) {
                return ResponseEntity.unprocessableEntity()
                        .body(result);
            }

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error",
                            exception.getMessage()
                    ));
        }
    }
}