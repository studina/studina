package com.studina.dinaapi.studyplan;

import java.util.List;

public record GeminiStudyPlanResponse(
        boolean canBeScheduled,
        List<GeminiStudySession> sessions,
        String message
) {
}