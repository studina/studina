package com.studina.dinaapi.studyplan;

import java.util.List;

public record GeminiStudyPlanResponse(
        boolean canBeScheduled,
        List<GeminiTopicPlan> topicPlans,
        List<GeminiStudySession> sessions,
        String message
) {
}