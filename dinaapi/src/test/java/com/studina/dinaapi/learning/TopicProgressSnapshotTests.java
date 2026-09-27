package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class TopicProgressSnapshotTests {

    private static final Offset<Double> TOLERANCE = Offset.offset(1e-9);

    private static Map<String, Object> progress(double mastery, int timesSeen, String lastSeenAt) {
        return Map.of("mastery_score", mastery, "times_seen", timesSeen, "last_seen_at", lastSeenAt);
    }

    @Test
    void aggregatesMasteryUniqueResourcesAttemptsAndLatestPractice() {
        TopicProgressSnapshot snapshot = TopicProgressSnapshot.calculate(
                Set.of("card-a", "card-b", "card-c"),
                Map.of(
                        "card-a", progress(0.8, 3, "2026-09-25T10:00:00Z"),
                        "card-b", progress(0.4, 1, "2026-09-26T10:00:00Z")));

        assertThat(snapshot.masteryScore()).isCloseTo(0.6, TOLERANCE);
        assertThat(snapshot.uniqueResourcesSeen()).isEqualTo(2);
        assertThat(snapshot.totalAttempts()).isEqualTo(4);
        assertThat(snapshot.lastSeenAt()).isEqualTo("2026-09-26T10:00:00Z");
    }

    @Test
    void treatsFlashcardsAndQuizQuestionsAsResourcesOfTheSameKind() {
        TopicProgressSnapshot snapshot = TopicProgressSnapshot.calculate(
                Set.of("card-a", "question-a", "question-b"),
                Map.of(
                        "card-a", progress(0.8, 2, "2026-09-25T10:00:00Z"),
                        "question-a", progress(0.42, 2, "2026-09-26T10:00:00Z")));

        assertThat(snapshot.masteryScore()).isCloseTo(0.61, TOLERANCE);
        assertThat(snapshot.uniqueResourcesSeen()).isEqualTo(2);
        assertThat(snapshot.totalAttempts()).isEqualTo(4);
    }

    /**
     * Regression: a question shared by two topics used to be replayed once per topic, which
     * doubled its attempts and inflated mastery. Reading one aggregate per resource makes each
     * topic count the answer exactly once.
     */
    @Test
    void countsAQuestionSharedByTwoTopicsOnceInEachTopic() {
        Map<String, Map<String, Object>> progressByResource = Map.of(
                "question-shared", progress(0.7, 1, "2026-09-26T10:00:00Z"));

        TopicProgressSnapshot first = TopicProgressSnapshot.calculate(
                Set.of("question-shared"), progressByResource);
        TopicProgressSnapshot second = TopicProgressSnapshot.calculate(
                Set.of("question-shared"), progressByResource);

        assertThat(first.totalAttempts()).isEqualTo(1);
        assertThat(second.totalAttempts()).isEqualTo(1);
        assertThat(first.masteryScore()).isCloseTo(0.7, TOLERANCE);
        assertThat(second.masteryScore()).isCloseTo(0.7, TOLERANCE);
    }

    @Test
    void reportsAnUntouchedTopicAsEmptyInsteadOfFailing() {
        TopicProgressSnapshot snapshot = TopicProgressSnapshot.calculate(
                Set.of("card-a", "card-b"), Map.of());

        assertThat(snapshot.masteryScore()).isEqualTo(0.0);
        assertThat(snapshot.uniqueResourcesSeen()).isZero();
        assertThat(snapshot.totalAttempts()).isZero();
        assertThat(snapshot.lastSeenAt()).isNull();
    }
}
