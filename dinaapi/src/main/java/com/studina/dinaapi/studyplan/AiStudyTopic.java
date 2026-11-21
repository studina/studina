package com.studina.dinaapi.studyplan;

public record AiStudyTopic(
        String topicId,
        String title,
        String description,
        Double masteryScore,
        String level,
        Double coverage,
        int totalResources,
        int totalAttempts
) {
}