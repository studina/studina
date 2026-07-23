package com.studina.dinaapi.studyplan;

import java.util.List;

public record GeneratedStudyPlan(
        boolean canBeScheduled,
        int requiredMinutes,
        int availableMinutes,
        int missingMinutes,
        List<StudySession> sessions,
        String message
) {
}