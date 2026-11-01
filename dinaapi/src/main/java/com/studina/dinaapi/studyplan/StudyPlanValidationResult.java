package com.studina.dinaapi.studyplan;

import java.util.List;

public record StudyPlanValidationResult(
        boolean valid,
        List<String> errors
) {

    public static StudyPlanValidationResult success() {
        return new StudyPlanValidationResult(
                true,
                List.of()
        );
    }

    public static StudyPlanValidationResult failure(
            List<String> errors
    ) {
        return new StudyPlanValidationResult(
                false,
                List.copyOf(errors)
        );
    }
}