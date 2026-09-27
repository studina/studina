package com.studina.dinaapi.learning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

record TopicProgressSnapshot(
        double masteryScore,
        double coverage,
        int uniqueResourcesSeen,
        int totalResources,
        int totalAttempts,
        String lastSeenAt) {

    static TopicProgressSnapshot calculate(
            Set<String> flashcardIds,
            Map<String, Map<String, Object>> progressByCard,
            Set<String> quizQuestionIds,
            Map<String, List<Map<String, Object>>> quizEvidenceByQuestion,
            LearningProgressPolicy policy) {
        List<Double> resourceMasteries = new ArrayList<>();
        int attempts = 0;
        String lastSeen = null;
        for (String cardId : flashcardIds) {
            Map<String, Object> progress = progressByCard.get(cardId);
            if (progress == null) continue;
            resourceMasteries.add(number(progress.get("mastery_score")));
            attempts += integer(progress.get("times_seen"));
            lastSeen = later(lastSeen, value(progress.get("last_seen_at")));
        }
        for (String questionId : quizQuestionIds) {
            List<Map<String, Object>> evidence = quizEvidenceByQuestion.get(questionId);
            if (evidence == null || evidence.isEmpty()) continue;
            evidence.sort(Comparator.comparing(row -> value(row.get("created_at"))));
            Double mastery = null;
            for (Map<String, Object> attempt : evidence) {
                Object rawRating = attempt.get("rating");
                if (!(rawRating instanceof Number rating)) continue;
                mastery = policy.updatedMastery(mastery, rating.intValue());
                attempts++;
                lastSeen = later(lastSeen, value(attempt.get("created_at")));
            }
            if (mastery != null) resourceMasteries.add(mastery);
        }
        double mastery = resourceMasteries.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        int total = flashcardIds.size() + quizQuestionIds.size();
        return new TopicProgressSnapshot(
                mastery,
                policy.coverage(resourceMasteries.size(), total),
                resourceMasteries.size(),
                total,
                attempts,
                lastSeen);
    }

    static TopicProgressSnapshot calculate(
            Set<String> topicCardIds,
            Map<String, Map<String, Object>> progressByCard,
            LearningProgressPolicy policy) {
        return calculate(topicCardIds, progressByCard, Set.of(), Map.of(), policy);
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String value(Object value) {
        return value == null ? null : value.toString();
    }

    private static String later(String first, String second) {
        if (first == null) return second;
        if (second == null) return first;
        return first.compareTo(second) >= 0 ? first : second;
    }
}
