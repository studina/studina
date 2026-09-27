package com.studina.dinaapi.learning;

import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LearningProgressPolicy {

    private final double initialMasteryWeight;
    private final double retentionFactor;
    private final double currentAttemptFactor;
    private final int cooldownCards;
    private final double minimumReviewWeight;

    public LearningProgressPolicy(
            @Value("${learning.mastery.initial-weight:0.70}") double initialMasteryWeight,
            @Value("${learning.mastery.previous-weight:0.60}") double retentionFactor,
            @Value("${learning.mastery.current-weight:0.40}") double currentAttemptFactor,
            @Value("${learning.progress.review-cooldown-cards:3}") int cooldownCards,
            @Value("${learning.progress.minimum-review-weight:0.01}") double minimumReviewWeight) {
        if (initialMasteryWeight < 0 || initialMasteryWeight > 1
                || retentionFactor < 0 || currentAttemptFactor < 0
                || Math.abs(retentionFactor + currentAttemptFactor - 1.0) > 0.000001) {
            throw new IllegalArgumentException("Los pesos de mastery deben ser válidos y los pesos EMA deben sumar 1");
        }
        if (cooldownCards < 0 || minimumReviewWeight <= 0) {
            throw new IllegalArgumentException("La configuración de progreso es inválida");
        }
        this.initialMasteryWeight = initialMasteryWeight;
        this.retentionFactor = retentionFactor;
        this.currentAttemptFactor = currentAttemptFactor;
        this.cooldownCards = cooldownCards;
        this.minimumReviewWeight = minimumReviewWeight;
    }

    public double ratingScore(int rating) {
        validateRating(rating);
        return rating / 4.0;
    }

    /**
     * Score of a quiz answer. A multiple-choice answer is a boolean fact, so it maps to the
     * ends of the same 0..1 scale the flashcard ratings use.
     */
    public double scoreOf(boolean correct) {
        return correct ? 1.0 : 0.0;
    }

    public double updatedMastery(Double previousMastery, int rating) {
        return advanceMastery(previousMastery, ratingScore(rating));
    }

    /**
     * The EMA shared by every resource type: the first observation is damped by the initial
     * weight, later ones blend the previous estimate with the new score.
     */
    public double advanceMastery(Double previousMastery, double score) {
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("El score debe estar entre 0 y 1");
        }
        if (previousMastery == null) {
            return clamp(score * initialMasteryWeight);
        }
        validateMastery(previousMastery);
        return clamp(previousMastery * retentionFactor + score * currentAttemptFactor);
    }

    public double coverage(int uniqueCardsSeen, int cardsForTopic) {
        if (uniqueCardsSeen < 0 || cardsForTopic < 0) {
            throw new IllegalArgumentException("Las cantidades de tarjetas no pueden ser negativas");
        }
        if (cardsForTopic == 0) {
            return 0.0;
        }
        return Math.min((double) uniqueCardsSeen / cardsForTopic, 1.0);
    }

    public double reviewWeight(double mastery) {
        validateMastery(mastery);
        return Math.max(minimumReviewWeight, Math.pow(1.0 - mastery, 2));
    }

    public int cooldownCards() {
        return cooldownCards;
    }

    public ReviewCandidate selectNext(
            List<ReviewCandidate> candidates,
            List<String> recentlyShownCardIds,
            Random random) {
        Objects.requireNonNull(candidates, "candidates es obligatorio");
        Objects.requireNonNull(recentlyShownCardIds, "recentlyShownCardIds es obligatorio");
        Objects.requireNonNull(random, "random es obligatorio");

        List<ReviewCandidate> available = candidates.stream()
                .filter(candidate -> !recentlyShownCardIds.contains(candidate.cardId()))
                .toList();
        List<ReviewCandidate> pool = available.isEmpty() ? candidates : available;

        if (pool.isEmpty()) {
            return null;
        }

        double totalWeight = pool.stream()
                .mapToDouble(candidate -> reviewWeight(candidate.mastery()))
                .sum();
        double target = random.nextDouble() * totalWeight;
        double accumulated = 0.0;
        for (ReviewCandidate candidate : pool) {
            accumulated += reviewWeight(candidate.mastery());
            if (target < accumulated) {
                return candidate;
            }
        }
        return pool.getLast();
    }

    private void validateRating(int rating) {
        if (rating < 0 || rating > 4) {
            throw new IllegalArgumentException("El rating debe estar entre 0 y 4");
        }
    }

    private void validateMastery(double mastery) {
        if (mastery < 0 || mastery > 1) {
            throw new IllegalArgumentException("El mastery debe estar entre 0 y 1");
        }
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}