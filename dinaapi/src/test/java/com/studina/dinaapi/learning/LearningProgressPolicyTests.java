package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class LearningProgressPolicyTests {

    private final LearningProgressPolicy policy =
            new LearningProgressPolicy(0.70, 0.60, 0.40, 3, 0.01);

    @Test
    void scalesTheFirstAttemptByTheConfiguredInitialMasteryWeight() {
        assertThat(policy.updatedMastery(null, 0)).isCloseTo(0.0, within(1e-9));
        assertThat(policy.updatedMastery(null, 1)).isCloseTo(0.175, within(1e-9));
        assertThat(policy.updatedMastery(null, 2)).isCloseTo(0.35, within(1e-9));
        assertThat(policy.updatedMastery(null, 3)).isCloseTo(0.525, within(1e-9));
        assertThat(policy.updatedMastery(null, 4)).isCloseTo(0.70, within(1e-9));
    }

    @Test
    void appliesEmaAfterTheFirstAttempt() {
        double mastery = policy.updatedMastery(null, 4);
        assertThat(mastery).isCloseTo(0.70, within(1e-9));

        mastery = policy.updatedMastery(mastery, 4);
        assertThat(mastery).isCloseTo(0.82, within(1e-9));
        mastery = policy.updatedMastery(mastery, 4);
        assertThat(mastery).isCloseTo(0.892, within(1e-9));
        mastery = policy.updatedMastery(mastery, 4);
        assertThat(mastery).isCloseTo(0.9352, within(1e-9));
    }

    @Test
    void preservesHistoricalMasteryInsteadOfReplacingItWithTheLastRating() {
        assertThat(policy.updatedMastery(0.8, 0)).isEqualTo(0.48);
        assertThat(policy.updatedMastery(0.2, 4)).isEqualTo(0.52);
    }

    @Test
    void scoresAQuizAnswerAtTheEndsOfTheSameScaleTheRatingsUse() {
        assertThat(policy.scoreOf(true)).isEqualTo(policy.ratingScore(4));
        assertThat(policy.scoreOf(false)).isEqualTo(policy.ratingScore(0));
    }

    @Test
    void appliesTheSameEmaToRatingsAndToQuizAnswers() {
        double fromRating = policy.updatedMastery(null, 4);
        double fromAnswer = policy.advanceMastery(null, policy.scoreOf(true));
        assertThat(fromAnswer).isCloseTo(fromRating, within(1e-9));

        assertThat(policy.advanceMastery(0.8, policy.scoreOf(false))).isCloseTo(0.48, within(1e-9));
        assertThat(policy.advanceMastery(0.2, policy.scoreOf(true))).isCloseTo(0.52, within(1e-9));
    }

    @Test
    void calculatesCoverageFromUniqueCardsAndTopicSize() {
        assertThat(policy.coverage(1, 10)).isEqualTo(0.1);
        assertThat(policy.coverage(3, 10)).isEqualTo(0.3);
        assertThat(policy.coverage(15, 10)).isEqualTo(1.0);
        assertThat(policy.coverage(0, 0)).isEqualTo(0.0);
    }

    @Test
    void excludesRecentlyShownCardsWhenAnotherCardExists() {
        ReviewCandidate selected = policy.selectNext(
                List.of(
                        new ReviewCandidate("card-a", 0.0),
                        new ReviewCandidate("card-b", 1.0)
                ),
                List.of("card-a"),
                new Random(1)
        );

        assertThat(selected.cardId()).isEqualTo("card-b");
    }

    @Test
    void relaxesCooldownWhenEveryCardWasShownRecently() {
        ReviewCandidate selected = policy.selectNext(
                List.of(new ReviewCandidate("card-a", 0.3)),
                List.of("card-a"),
                new Random(1)
        );

        assertThat(selected.cardId()).isEqualTo("card-a");
    }

    @Test
    void returnsNullWhenThereAreNoCandidates() {
        assertThat(policy.selectNext(List.of(), List.of(), new Random(1))).isNull();
    }

    @Test
    void favorsWeakCardsInWeightedReview() {
        assertThat(policy.reviewWeight(0.0)).isGreaterThan(policy.reviewWeight(0.8));
        assertThat(policy.reviewWeight(1.0)).isEqualTo(0.01);
    }
}