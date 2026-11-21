package com.studina.dinaapi.studyplan;

import java.time.LocalDate;
import java.util.List;

public record GenerateAiStudyPlanRequest(
        String milestoneType,
        String milestoneTitle,
        String milestoneDescription,
        LocalDate startDate,
        LocalDate examDate,
        int reviewSessions,
        List<AiStudyTopic> topics,
        List<StudyAvailability> availability
) {
}