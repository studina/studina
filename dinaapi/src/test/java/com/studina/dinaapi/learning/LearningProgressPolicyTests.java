package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

class LearningProgressPolicyTests {

    private final LearningProgressPolicy policy =
            new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 30, 0.70, 0.40);

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
    void scoresAnOpenAnswerVerdictOnTheSameScaleAsTheRatings() {
        assertThat(policy.scoreOfVerdict("perfect")).isEqualTo(policy.ratingScore(4));
        assertThat(policy.scoreOfVerdict("correct")).isEqualTo(policy.ratingScore(3));
        assertThat(policy.scoreOfVerdict("partially_correct")).isEqualTo(policy.ratingScore(2));
        assertThat(policy.scoreOfVerdict("partially_incorrect")).isEqualTo(policy.ratingScore(1));
        assertThat(policy.scoreOfVerdict("incorrect")).isEqualTo(policy.ratingScore(0));
    }

    @Test
    void rejectsAnUnknownOpenAnswerVerdict() {
        assertThatThrownBy(() -> policy.scoreOfVerdict("excellent"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.scoreOfVerdict(null))
                .isInstanceOf(IllegalArgumentException.class);
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
    void favorsWeakCardsInWeightedReview() {
        assertThat(policy.reviewWeight(0.0)).isGreaterThan(policy.reviewWeight(0.8));
        assertThat(policy.reviewWeight(1.0)).isEqualTo(0.01);
    }

    @Test
    void repeatsWeakCardsMoreTimesThanMasteredOnes() {
        assertThat(policy.reviewRepetitions(0.0)).isEqualTo(3);
        assertThat(policy.reviewRepetitions(0.10)).isEqualTo(3);
        assertThat(policy.reviewRepetitions(0.30)).isEqualTo(2);
        assertThat(policy.reviewRepetitions(0.50)).isEqualTo(2);
        assertThat(policy.reviewRepetitions(0.70)).isEqualTo(1);
        assertThat(policy.reviewRepetitions(1.0)).isEqualTo(1);
    }

    /**
     * La primera pasada de un set no se toca: sin intentos no hay evidencia de que nada esté
     * flojo, así que la ronda es el set tal cual, una vez cada tarjeta y en orden de creación.
     */
    @Test
    void aSetWithoutHistoryIsReviewedOnceInCreationOrder() {
        List<ReviewCandidate> round = policy.buildReviewRound(List.of(
                new ReviewCandidate("card-a", null),
                new ReviewCandidate("card-b", null),
                new ReviewCandidate("card-c", null)
        ));

        assertThat(round).extracting(ReviewCandidate::cardId)
                .containsExactly("card-a", "card-b", "card-c");
    }

    @Test
    void anUnpracticedCardIsNotRepeatedEvenWhenTheRestOfTheSetIsWeak() {
        List<ReviewCandidate> round = policy.buildReviewRound(List.of(
                new ReviewCandidate("nueva", null),
                new ReviewCandidate("floja", 0.0)
        ));

        assertThat(round).extracting(ReviewCandidate::cardId)
                .filteredOn("nueva"::equals).hasSize(1);
        assertThat(round).extracting(ReviewCandidate::cardId)
                .filteredOn("floja"::equals).hasSize(3);
    }

    @Test
    void ordersTheRoundFromWeakestToStrongestWithoutBackToBackRepeats() {
        List<ReviewCandidate> round = policy.buildReviewRound(List.of(
                new ReviewCandidate("dominada", 0.9),
                new ReviewCandidate("floja", 0.0),
                new ReviewCandidate("media", 0.4)
        ));

        List<String> ids = round.stream().map(ReviewCandidate::cardId).toList();
        assertThat(ids.get(0)).isEqualTo("floja");
        assertThat(ids.get(1)).isEqualTo("media");
        for (int i = 1; i < ids.size(); i++) {
            assertThat(ids.get(i))
                    .as("ninguna tarjeta sale dos veces seguidas")
                    .isNotEqualTo(ids.get(i - 1));
        }
    }

    @Test
    void truncatesTheRoundFromTheEndWhenItGetsTooLong() {
        LearningProgressPolicy cappedPolicy =
                new LearningProgressPolicy(0.70, 0.60, 0.40, 0.01, 3, 4, 0.70, 0.40);

        List<ReviewCandidate> round = cappedPolicy.buildReviewRound(List.of(
                new ReviewCandidate("card-a", 0.0),
                new ReviewCandidate("card-b", 0.0),
                new ReviewCandidate("card-c", 0.0)
        ));

        assertThat(round).hasSize(4);
        assertThat(round).extracting(ReviewCandidate::cardId)
                .containsExactly("card-a", "card-b", "card-c", "card-a");
    }

    @Test
    void classifiesMasteryAtTheThresholdsTheProgressViewGroupsBy() {
        // Los cortes son inclusivos hacia arriba: 0.70 ya es dominado y 0.40 ya no es flojo.
        assertThat(policy.masteryLevel(0.70)).isEqualTo("strong");
        assertThat(policy.masteryLevel(0.69)).isEqualTo("progressing");
        assertThat(policy.masteryLevel(0.40)).isEqualTo("progressing");
        assertThat(policy.masteryLevel(0.39)).isEqualTo("weak");
        assertThat(policy.masteryLevel(0.0)).isEqualTo("weak");
    }

    @Test
    void doesNotClassifyAnUnpracticedTopicAsWeak() {
        // Un topic sin practicar no es un topic flojo: no hay evidencia. Es la misma
        // distinción que hace reviewRepetitions con una tarjeta sin historial.
        assertThat(policy.masteryLevel(null)).isNull();
    }
}
