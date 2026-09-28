package com.studina.dinaapi.ai.rag;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Validates a batch of resources returned by the model and assigns each one a server id.
 *
 * <p>The rules that flashcards and quiz questions share live in {@link #identify}: the batch
 * must hold at least the requested amount, every resource must carry exactly one topic from
 * the allowed catalog, and ids come from the server. What differs between them is only the
 * shape of their content, expressed as a predicate.
 */
final class GeneratedResourcePolicy {

    /** Tope del título que propone la IA: entra en una fila de la lista sin cortarse. */
    public static final int MAX_GENERATED_TITLE_LENGTH = 60;

    /**
     * Limpia el título que devuelve la IA.
     *
     * <p>Los modelos suelen envolverlo en comillas o agregarle un punto final, y a veces se
     * pasan de largo pese a pedirlo corto. Devuelve {@code null} si no quedó nada usable, para
     * que el caller aplique su propio fallback en vez de guardar un título vacío.
     */
    public static String cleanTitle(String title) {
        if (title == null) {
            return null;
        }
        // Comillas y punto final salen en la misma pasada: vienen combinados ("...".)
        // y limpiarlos por separado deja el sobrante del otro.
        String cleaned = title
                .replaceAll("\\s+", " ")
                .replaceAll("^[\"'«»“”\\s]+", "")
                .replaceAll("[\"'«»“”.\\s]+$", "");
        if (cleaned.isBlank()) {
            return null;
        }
        if (cleaned.length() > MAX_GENERATED_TITLE_LENGTH) {
            cleaned = cleaned.substring(0, MAX_GENERATED_TITLE_LENGTH).trim();
        }
        return cleaned;
    }

    private GeneratedResourcePolicy() {
    }

    static List<Flashcard> identifyCards(
            List<Flashcard> cards, int requestedCount, Set<String> validTopicIds) {
        return identify(
                cards,
                requestedCount,
                validTopicIds,
                GeneratedResourcePolicy::hasValidContent,
                "La IA no devolvió flashcards válidas. Intentá generarlas nuevamente");
    }

    static List<QuizQuestion> identifyQuestions(
            List<QuizQuestion> questions,
            int requestedCount,
            int requestedAnswerCount,
            Set<String> validTopicIds) {
        return identify(
                questions,
                requestedCount,
                validTopicIds,
                question -> hasValidContent(question, requestedAnswerCount),
                "La IA no devolvió un cuestionario válido. Intentá generarlo nuevamente");
    }

    private static <T extends GeneratedResource<T>> List<T> identify(
            List<T> generated,
            int requestedCount,
            Set<String> validTopicIds,
            Predicate<T> hasValidContent,
            String errorMessage) {

        if (generated == null || generated.size() < requestedCount) {
            throw new IllegalArgumentException(errorMessage);
        }
        List<T> batch = generated.stream().limit(requestedCount).toList();
        if (batch.stream().anyMatch(resource -> resource == null
                || !belongsToAllowedTopic(resource, validTopicIds)
                || !hasValidContent.test(resource))) {
            throw new IllegalArgumentException(errorMessage);
        }
        return batch.stream()
                .map(resource -> resource.identified(UUID.randomUUID().toString()))
                .toList();
    }

    private static boolean belongsToAllowedTopic(
            GeneratedResource<?> resource, Set<String> validTopicIds) {
        return present(resource.topicId()) && validTopicIds.contains(resource.topicId());
    }

    private static boolean hasValidContent(Flashcard card) {
        return present(card.term()) && present(card.definition());
    }

    private static boolean hasValidContent(QuizQuestion question, int requestedAnswerCount) {
        return present(question.question())
                && question.options() != null
                && question.options().size() == requestedAnswerCount
                && question.options().stream().allMatch(option ->
                        option != null && option.id() != null && present(option.text()))
                && question.correctOptionId() != null
                && question.options().stream().anyMatch(option ->
                        option.id().equals(question.correctOptionId()));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
