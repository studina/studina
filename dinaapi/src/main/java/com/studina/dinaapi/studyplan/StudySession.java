package com.studina.dinaapi.studyplan;

import java.time.LocalDateTime;

public record StudySession(
        String title,
        LocalDateTime startDateTime,
        LocalDateTime endDateTime,
        StudySessionType type,
        Integer topicIndex,
        Integer partNumber
) {
}