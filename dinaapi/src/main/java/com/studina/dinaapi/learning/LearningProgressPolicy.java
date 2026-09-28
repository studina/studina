package com.studina.dinaapi.learning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LearningProgressPolicy {

    private final double initialMasteryWeight;
    private final double retentionFactor;
    private final double currentAttemptFactor;
    private final double minimumReviewWeight;
    private final int maxReviewRepetitions;
    private final int maxRoundCards;

    public LearningProgressPolicy(
            @Value("${learning.mastery.initial-weight:0.70}") double initialMasteryWeight,
            @Value("${learning.mastery.previous-weight:0.60}") double retentionFactor,
            @Value("${learning.mastery.current-weight:0.40}") double currentAttemptFactor,
            @Value("${learning.progress.minimum-review-weight:0.01}") double minimumReviewWeight,
            @Value("${learning.progress.max-review-repetitions:3}") int maxReviewRepetitions,
            @Value("${learning.progress.max-round-cards:30}") int maxRoundCards) {
        if (initialMasteryWeight < 0 || initialMasteryWeight > 1
                || retentionFactor < 0 || currentAttemptFactor < 0
                || Math.abs(retentionFactor + currentAttemptFactor - 1.0) > 0.000001) {
            throw new IllegalArgumentException("Los pesos de mastery deben ser válidos y los pesos EMA deben sumar 1");
        }
        if (minimumReviewWeight <= 0 || maxReviewRepetitions < 1 || maxRoundCards < 1) {
            throw new IllegalArgumentException("La configuración de progreso es inválida");
        }
        this.initialMasteryWeight = initialMasteryWeight;
        this.retentionFactor = retentionFactor;
        this.currentAttemptFactor = currentAttemptFactor;
        this.minimumReviewWeight = minimumReviewWeight;
        this.maxReviewRepetitions = maxReviewRepetitions;
        this.maxRoundCards = maxRoundCards;
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

    /**
     * Cuántas veces entra una tarjeta en la ronda.
     *
     * <p>Una tarjeta sin practicar entra una sola vez: no hay evidencia de que esté floja,
     * y repetir algo que nunca se vio no es reforzar. Gracias a eso, la ronda de un set
     * recién generado es el set entero una vez, en su orden original.
     */
    public int reviewRepetitions(Double mastery) {
        if (mastery == null) {
            return 1;
        }
        return 1 + (int) Math.round(reviewWeight(mastery) * (maxReviewRepetitions - 1));
    }

    /**
     * Arma la ronda completa: una lista finita y ordenada, de la tarjeta más floja a la más
     * dominada, con las flojas repetidas.
     *
     * <p>Se arma por pasadas —todas, después las que van 2 o más veces, después las que van 3—
     * para que una tarjeta repetida no salga dos veces seguidas. El orden usa un sort estable,
     * así que entre empates (por ejemplo, un set nuevo donde nadie tiene historial) se conserva
     * el orden en que vinieron las tarjetas, que es el de creación.
     */
    public List<ReviewCandidate> buildReviewRound(List<ReviewCandidate> candidates) {
        Objects.requireNonNull(candidates, "candidates es obligatorio");
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<ReviewCandidate> ordered = candidates.stream()
                .sorted(Comparator.comparingDouble(ReviewCandidate::masteryForOrdering))
                .toList();

        List<ReviewCandidate> round = new ArrayList<>();
        for (int pass = 1; pass <= maxReviewRepetitions; pass++) {
            int currentPass = pass;
            List<ReviewCandidate> current = ordered.stream()
                    .filter(candidate -> reviewRepetitions(candidate.mastery()) >= currentPass)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (current.isEmpty()) {
                continue;
            }
            // La última tarjeta de una pasada puede ser también la primera de la siguiente.
            // Rotar la pasada evita que salga dos veces seguidas; con una sola tarjeta
            // disponible no hay nada que rotar y la repetición es inevitable.
            if (!round.isEmpty() && current.size() > 1
                    && current.get(0).cardId().equals(round.get(round.size() - 1).cardId())) {
                current.add(current.remove(0));
            }
            round.addAll(current);
        }

        return round.size() > maxRoundCards ? round.subList(0, maxRoundCards) : round;
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