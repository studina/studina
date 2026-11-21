package com.studina.dinaapi.studyplan;

/** A provider failure; its raw response must not be returned to the browser. */
public final class StudyPlanGenerationException extends IllegalStateException {

    StudyPlanGenerationException(Throwable cause) {
        super("La IA no pudo generar el plan de estudio. Volvé a intentarlo.", cause);
    }
}
