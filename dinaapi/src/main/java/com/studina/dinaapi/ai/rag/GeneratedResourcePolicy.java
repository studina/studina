package com.studina.dinaapi.ai.rag;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Validates a batch of resources returned by the model and assigns each one a server id.
 *
 * <p>The rules that flashcards and quiz questions share live in {@link #identify}: the batch
 * must hold at least the requested amount, every resource must carry exactly one topic from
 * the allowed catalog, and ids come from the server. What differs between them is only the
 * shape of their content. Rejections carry only fixed field names and numeric diagnostics,
 * never model output or topic identifiers.
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

    enum Reason {
        BATCH_MISSING, COUNT_SHORT, NULL_RESOURCE, TOPIC_MISSING, TOPIC_NOT_ALLOWED,
        FIELD_MISSING, OPTION_COUNT, OPTION_INVALID, CORRECT_OPTION_INVALID, KEY_POINTS_INVALID
    }

    static final class ValidationException extends IllegalArgumentException {
        private final Reason reason;
        private final String field;
        private final int requestedCount;
        private final int receivedCount;
        private final int resourceIndex;

        private ValidationException(String message, Reason reason, String field,
                int requestedCount, int receivedCount, int resourceIndex) {
            super(message);
            this.reason = reason;
            this.field = field;
            this.requestedCount = requestedCount;
            this.receivedCount = receivedCount;
            this.resourceIndex = resourceIndex;
        }

        Reason reason() { return reason; }
        String field() { return field; }
        int requestedCount() { return requestedCount; }
        int receivedCount() { return receivedCount; }
        int resourceIndex() { return resourceIndex; }
    }

    private record ContentProblem(Reason reason, String field) {
    }

    static List<Flashcard> identifyCards(
            List<Flashcard> cards, int requestedCount, Set<String> validTopicIds) {
        return identify(
                cards,
                requestedCount,
                validTopicIds,
                GeneratedResourcePolicy::contentProblem,
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
                question -> contentProblem(question, requestedAnswerCount),
                "La IA no devolvió un cuestionario válido. Intentá generarlo nuevamente");
    }

    static List<TrueFalseStatement> identifyStatements(
            List<TrueFalseStatement> statements, int requestedCount, Set<String> validTopicIds) {
        return identify(
                statements,
                requestedCount,
                validTopicIds,
                GeneratedResourcePolicy::contentProblem,
                "La IA no devolvió enunciados de verdadero o falso válidos. Intentá generarlos nuevamente");
    }

    static List<OpenQuestion> identifyOpenQuestions(
            List<OpenQuestion> questions, int requestedCount, Set<String> validTopicIds) {
        return identify(
                questions,
                requestedCount,
                validTopicIds,
                GeneratedResourcePolicy::contentProblem,
                "La IA no devolvió preguntas abiertas válidas. Intentá generarlas nuevamente");
    }

    private static <T extends GeneratedResource<T>> List<T> identify(
            List<T> generated,
            int requestedCount,
            Set<String> validTopicIds,
            Function<T, ContentProblem> contentProblem,
            String errorMessage) {

        if (generated == null) {
            throw new ValidationException(errorMessage, Reason.BATCH_MISSING, "batch",
                    requestedCount, 0, -1);
        }
        if (generated.size() < requestedCount) {
            throw new ValidationException(errorMessage, Reason.COUNT_SHORT, "batch",
                    requestedCount, generated.size(), -1);
        }
        List<T> batch = generated.stream().limit(requestedCount).toList();
        for (int index = 0; index < batch.size(); index++) {
            T resource = batch.get(index);
            ContentProblem problem;
            if (resource == null) {
                problem = new ContentProblem(Reason.NULL_RESOURCE, "resource");
            } else if (!present(resource.topicId())) {
                problem = new ContentProblem(Reason.TOPIC_MISSING, "topic_id");
            } else if (!validTopicIds.contains(resource.topicId())) {
                problem = new ContentProblem(Reason.TOPIC_NOT_ALLOWED, "topic_id");
            } else {
                problem = contentProblem.apply(resource);
            }
            if (problem != null) {
                throw new ValidationException(errorMessage, problem.reason(), problem.field(),
                        requestedCount, generated.size(), index);
            }
        }
        return batch.stream()
                .map(resource -> resource.identified(UUID.randomUUID().toString()))
                .toList();
    }

    private static ContentProblem contentProblem(Flashcard card) {
        if (!present(card.term())) {
            return new ContentProblem(Reason.FIELD_MISSING, "term");
        }
        return present(card.definition()) ? null : new ContentProblem(Reason.FIELD_MISSING, "definition");
    }

    private static ContentProblem contentProblem(QuizQuestion question, int requestedAnswerCount) {
        if (!present(question.question())) {
            return new ContentProblem(Reason.FIELD_MISSING, "question");
        }
        if (question.options() == null) {
            return new ContentProblem(Reason.FIELD_MISSING, "options");
        }
        if (question.options().size() != requestedAnswerCount) {
            return new ContentProblem(Reason.OPTION_COUNT, "options");
        }
        for (int index = 0; index < question.options().size(); index++) {
            QuizOption option = question.options().get(index);
            if (option == null || option.id() == null || !present(option.text())) {
                return new ContentProblem(Reason.OPTION_INVALID, "options[" + index + "]");
            }
        }
        if (question.correctOptionId() == null) {
            return new ContentProblem(Reason.FIELD_MISSING, "correctOptionId");
        }
        return question.options().stream().anyMatch(option -> option.id().equals(question.correctOptionId()))
                ? null : new ContentProblem(Reason.CORRECT_OPTION_INVALID, "correctOptionId");
    }

    private static ContentProblem contentProblem(TrueFalseStatement statement) {
        if (!present(statement.statement())) {
            return new ContentProblem(Reason.FIELD_MISSING, "statement");
        }
        return statement.isTrue() != null ? null : new ContentProblem(Reason.FIELD_MISSING, "isTrue");
    }

    /** Sin respuesta modelo ni puntos clave no hay contra qué corregir lo que escriba el alumno. */
    private static ContentProblem contentProblem(OpenQuestion question) {
        if (!present(question.question())) {
            return new ContentProblem(Reason.FIELD_MISSING, "question");
        }
        if (!present(question.referenceAnswer())) {
            return new ContentProblem(Reason.FIELD_MISSING, "reference_answer");
        }
        if (question.keyPoints() == null) {
            return new ContentProblem(Reason.FIELD_MISSING, "key_points");
        }
        return !question.keyPoints().isEmpty()
                && question.keyPoints().stream().allMatch(GeneratedResourcePolicy::present)
                ? null : new ContentProblem(Reason.KEY_POINTS_INVALID, "key_points");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
