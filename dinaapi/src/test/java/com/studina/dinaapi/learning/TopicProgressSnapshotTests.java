package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TopicProgressSnapshotTests {

    private final LearningProgressPolicy policy =
            new LearningProgressPolicy(0.70, 0.60, 0.40, 3, 0.01);

    @Test
        void aggregatesMasteryCoverageUniqueResourcesAttemptsAndLatestPractice() {
        TopicProgressSnapshot snapshot = TopicProgressSnapshot.calculate(
                Set.of("card-a", "card-b", "card-c"),
                Map.of(
                        "card-a", Map.of("mastery_score", 0.8, "times_seen", 3,
                                "last_seen_at", "2026-09-25T10:00:00Z"),
                        "card-b", Map.of("mastery_score", 0.4, "times_seen", 1,
                                "last_seen_at", "2026-09-26T10:00:00Z")
                ),
                policy
        );

        assertThat(snapshot.masteryScore()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(snapshot.coverage()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(snapshot.uniqueResourcesSeen()).isEqualTo(2);
        assertThat(snapshot.totalResources()).isEqualTo(3);
        assertThat(snapshot.totalAttempts()).isEqualTo(4);
        assertThat(snapshot.lastSeenAt()).isEqualTo("2026-09-26T10:00:00Z");
    }

    @Test
    void combinesQuizQuestionEvidenceWithFlashcardProgressForSharedTopics() {
        TopicProgressSnapshot snapshot = TopicProgressSnapshot.calculate(
                Set.of("card-a"),
                Map.of("card-a", Map.of("mastery_score", 0.8, "times_seen", 2,
                        "last_seen_at", "2026-09-25T10:00:00Z")),
                Set.of("question-a", "question-b"),
                Map.of("question-a", new java.util.ArrayList<>(List.of(
                        Map.of("rating", 4, "created_at", "2026-09-25T11:00:00Z"),
                        Map.of("rating", 0, "created_at", "2026-09-26T10:00:00Z")
                ))),
                policy
        );

        assertThat(snapshot.masteryScore()).isCloseTo(0.61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(snapshot.coverage()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(snapshot.uniqueResourcesSeen()).isEqualTo(2);
        assertThat(snapshot.totalResources()).isEqualTo(3);
        assertThat(snapshot.totalAttempts()).isEqualTo(4);
    }
}
