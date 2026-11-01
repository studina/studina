package com.studina.dinaapi.studyplan;

public record GeminiStudySession(
        String title,
        String startDateTime,
        String endDateTime,
        StudySessionType type,
        Integer topicIndex,
        Integer partNumber
) {
}