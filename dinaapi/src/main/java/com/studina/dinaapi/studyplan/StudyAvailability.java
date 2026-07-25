package com.studina.dinaapi.studyplan;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record StudyAvailability(
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime
) {
}