package com.studina.dinaapi.learning;

import java.util.Map;
import java.util.Set;

/**
 * Aggregates a topic from the per-resource progress of the resources that belong to it.
 *
 * <p>Every resource contributes exactly one mastery value, already computed by
 * {@link ResourceProgressService}. A question that belongs to several topics is therefore
 * counted once per topic and never replayed, which is what keeps {@code totalAttempts} honest.
 *
 * <p>{@code coverage} and {@code totalResources} are intentionally absent: they are derived from
 * {@code uniqueResourcesSeen} and the topic's resource count, so they are computed on read
 * instead of being stored per user.
 */
record TopicProgressSnapshot(
        double masteryScore,
        int uniqueResourcesSeen,
        int totalAttempts,
        String lastSeenAt) {

    static TopicProgressSnapshot calculate(
            Set<String> resourceIds,
            Map<String, Map<String, Object>> progressByResource) {

        double masterySum = 0.0;
        int seen = 0;
        int attempts = 0;
        String lastSeen = null;

        for (String resourceId : resourceIds) {
            Map<String, Object> progress = progressByResource.get(resourceId);
            if (progress == null) {
                continue;
            }
            masterySum += number(progress.get("mastery_score"));
            attempts += integer(progress.get("times_seen"));
            lastSeen = later(lastSeen, value(progress.get("last_seen_at")));
            seen++;
        }

        return new TopicProgressSnapshot(
                seen == 0 ? 0.0 : masterySum / seen,
                seen,
                attempts,
                lastSeen);
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
