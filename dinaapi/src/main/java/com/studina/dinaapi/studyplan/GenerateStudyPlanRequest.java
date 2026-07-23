package com.studina.dinaapi.studyplan;

import java.time.LocalDate;
import java.util.List;

public record GenerateStudyPlanRequest(
        LocalDate startDate,
        LocalDate examDate,
        List<StudyTopic> topics,
        List<StudyAvailability> availability,
        int reviewSessions
) {
}