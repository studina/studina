package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

/**
 * Flashcards and quiz questions go through the same policy, so the topic and identity rules
 * are asserted once for both and only their content rules are tested apart.
 */
class GeneratedResourcePolicyTests {

    private static final Set<String> CATALOG = Set.of("topic-a", "topic-b");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static Flashcard card(String topicId) {
        return new Flashcard(null, "Término", "Definición", topicId);
    }

    private static QuizQuestion question(String topicId) {
        return new QuizQuestion(null, "¿Pregunta?",
                List.of(new QuizOption("a", "Opción A"), new QuizOption("b", "Opción B")),
                "a", "Porque sí", topicId);
    }

    private static TrueFalseStatement statement(String topicId) {
        return new TrueFalseStatement(null, "Afirmación", false, "Porque no", topicId);
    }

    private static OpenQuestion openQuestion(String topicId) {
        return new OpenQuestion(null, "¿Por qué?", "Porque sí", List.of("idea central"), topicId);
    }

    private static List<OpenQuestion> identify(OpenQuestion... questions) {
        return GeneratedResourcePolicy.identifyOpenQuestions(List.of(questions), questions.length, CATALOG);
    }

    private static List<TrueFalseStatement> identify(TrueFalseStatement... statements) {
        return GeneratedResourcePolicy.identifyStatements(List.of(statements), statements.length, CATALOG);
    }

    private static List<Flashcard> identify(Flashcard... cards) {
        return GeneratedResourcePolicy.identifyCards(List.of(cards), cards.length, CATALOG);
    }

    private static List<QuizQuestion> identify(QuizQuestion... questions) {
        return GeneratedResourcePolicy.identifyQuestions(List.of(questions), questions.length, 2, CATALOG);
    }

    @Nested
    class TopicAndIdentityRulesSharedByBothResources {

        @Test
        void assignsAServerIdAndKeepsTheAllowedTopic() {
            assertThat(identify(card("topic-a")).getFirst().id()).isNotBlank();
            assertThat(identify(card("topic-a")).getFirst().topicId()).isEqualTo("topic-a");
            assertThat(identify(question("topic-b")).getFirst().id()).isNotBlank();
            assertThat(identify(question("topic-b")).getFirst().topicId()).isEqualTo("topic-b");
            assertThat(identify(statement("topic-a")).getFirst().id()).isNotBlank();
            assertThat(identify(statement("topic-a")).getFirst().topicId()).isEqualTo("topic-a");
            assertThat(identify(openQuestion("topic-a")).getFirst().id()).isNotBlank();
            assertThat(identify(openQuestion("topic-a")).getFirst().topicId()).isEqualTo("topic-a");
        }

        @Test
        void ignoresTheIdTheModelProposedAndGeneratesADistinctOnePerResource() {
            List<Flashcard> cards = identify(card("topic-a"), card("topic-a"));
            assertThat(cards.get(0).id()).isNotEqualTo(cards.get(1).id());

            List<QuizQuestion> questions = identify(question("topic-a"), question("topic-b"));
            assertThat(questions.get(0).id()).isNotEqualTo(questions.get(1).id());
        }

        @Test
        void rejectsAMissingBlankOrUnknownTopic() {
            for (String topicId : new String[] {null, " ", "topic-outside-catalog"}) {
                assertThatThrownBy(() -> identify(card(topicId)))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> identify(question(topicId)))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> identify(statement(topicId)))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> identify(openQuestion(topicId)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void rejectsABatchSmallerThanRequested() {
            assertThatThrownBy(() -> GeneratedResourcePolicy.identifyCards(
                    List.of(card("topic-a")), 2, CATALOG))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> GeneratedResourcePolicy.identifyQuestions(
                    List.of(question("topic-a")), 2, 2, CATALOG))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAnAbsentBatch() {
            assertThatThrownBy(() -> GeneratedResourcePolicy.identifyCards(null, 1, CATALOG))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> GeneratedResourcePolicy.identifyQuestions(null, 1, 2, CATALOG))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void truncatesASurplusBatchToWhatWasRequested() {
            assertThat(GeneratedResourcePolicy.identifyCards(
                    List.of(card("topic-a"), card("topic-b")), 1, CATALOG)).hasSize(1);
            assertThat(GeneratedResourcePolicy.identifyQuestions(
                    List.of(question("topic-a"), question("topic-b")), 1, 2, CATALOG)).hasSize(1);
        }
    }

    @Nested
    class FlashcardContent {

        @Test
        void rejectsAMissingTermOrDefinition() {
            assertThatThrownBy(() -> identify(new Flashcard(null, " ", "Definición", "topic-a")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> identify(new Flashcard(null, "Término", null, "topic-a")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class QuizQuestionContent {

        @Test
        void rejectsAnOptionCountThatDoesNotMatchTheRequest() {
            QuizQuestion threeOptions = new QuizQuestion(null, "¿Pregunta?",
                    List.of(new QuizOption("a", "A"), new QuizOption("b", "B"), new QuizOption("c", "C")),
                    "a", "Porque sí", "topic-a");
            assertThatThrownBy(() -> identify(threeOptions))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsACorrectOptionThatIsNotAmongTheOptions() {
            QuizQuestion danglingAnswer = new QuizQuestion(null, "¿Pregunta?",
                    List.of(new QuizOption("a", "A"), new QuizOption("b", "B")),
                    "z", "Porque sí", "topic-a");
            assertThatThrownBy(() -> identify(danglingAnswer))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsABlankOptionText() {
            QuizQuestion blankOption = new QuizQuestion(null, "¿Pregunta?",
                    List.of(new QuizOption("a", "A"), new QuizOption("b", " ")),
                    "a", "Porque sí", "topic-a");
            assertThatThrownBy(() -> identify(blankOption))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class TrueFalseContent {

        @Test
        void rejectsAMissingStatementOrAnswer() {
            assertThatThrownBy(() -> identify(new TrueFalseStatement(null, " ", true, "Porque sí", "topic-a")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> identify(new TrueFalseStatement(null, "Afirmación", null, "Porque sí", "topic-a")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void keepsTheAnswerTheModelGave() {
            assertThat(identify(statement("topic-a")).getFirst().isTrue()).isFalse();
        }
    }

    @Nested
    class OpenQuestionContent {

        @Test
        void rejectsAQuestionWithoutARubricToGradeAgainst() {
            assertThatThrownBy(() -> identify(new OpenQuestion(null, "¿Por qué?", " ", List.of("idea"), "topic-a")))
                    .as("sin respuesta modelo")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> identify(new OpenQuestion(null, "¿Por qué?", "Porque sí", List.of(), "topic-a")))
                    .as("sin puntos clave")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> identify(new OpenQuestion(null, "¿Por qué?", "Porque sí", List.of(" "), "topic-a")))
                    .as("con un punto clave vacío")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> identify(new OpenQuestion(null, "", "Porque sí", List.of("idea"), "topic-a")))
                    .as("sin pregunta")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class JsonContract {

        @Test
        void bothResourcesExposeASingleSnakeCaseTopicId() throws Exception {
            var cardJson = objectMapper.readTree(
                    objectMapper.writeValueAsString(identify(card("topic-a")).getFirst()));
            assertThat(cardJson.get("topic_id").asText()).isEqualTo("topic-a");
            assertThat(cardJson.has("topic_ids")).isFalse();

            var questionJson = objectMapper.readTree(
                    objectMapper.writeValueAsString(identify(question("topic-a")).getFirst()));
            assertThat(questionJson.get("topic_id").asText()).isEqualTo("topic-a");
            assertThat(questionJson.has("topic_ids")).isFalse();

            var statementJson = objectMapper.readTree(
                    objectMapper.writeValueAsString(identify(statement("topic-a")).getFirst()));
            assertThat(statementJson.get("topic_id").asText()).isEqualTo("topic-a");
            assertThat(statementJson.get("isTrue").asBoolean()).isFalse();
            assertThat(statementJson.has("topic_ids")).isFalse();

            var openQuestionJson = objectMapper.readTree(
                    objectMapper.writeValueAsString(identify(openQuestion("topic-a")).getFirst()));
            assertThat(openQuestionJson.get("topic_id").asText()).isEqualTo("topic-a");
            assertThat(openQuestionJson.get("reference_answer").asText()).isEqualTo("Porque sí");
            assertThat(openQuestionJson.get("key_points").size()).isEqualTo(1);
        }

        @Test
        void rejectsTheLegacyTopicListPayloadInsteadOfSilentlyAcceptingIt() {
            assertThatThrownBy(() -> objectMapper.readValue("""
                    {"term":"Replicación","definition":"Copias entre nodos.","topic_ids":["topic-a"]}
                    """, Flashcard.class))
                    .isInstanceOf(Exception.class);
            assertThatThrownBy(() -> objectMapper.readValue("""
                    {"question":"¿Pregunta?","options":[],"correctOptionId":"a","topic_ids":["topic-a"]}
                    """, QuizQuestion.class))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void cleansTheTitleTheModelProposes() {
        // Los modelos suelen envolverlo en comillas o cerrarlo con punto.
        assertThat(GeneratedResourcePolicy.cleanTitle("\"Consistencia eventual\""))
                .isEqualTo("Consistencia eventual");
        assertThat(GeneratedResourcePolicy.cleanTitle("“Elección de líder”."))
                .isEqualTo("Elección de líder");
        assertThat(GeneratedResourcePolicy.cleanTitle("  Comercio   internacional  "))
                .isEqualTo("Comercio internacional");
    }

    @Test
    void fallsBackToNothingWhenTheTitleIsUnusable() {
        // null deja que el caller aplique su propio fallback en vez de guardar un vacío.
        assertThat(GeneratedResourcePolicy.cleanTitle(null)).isNull();
        assertThat(GeneratedResourcePolicy.cleanTitle("   ")).isNull();
        assertThat(GeneratedResourcePolicy.cleanTitle("\"\"")).isNull();
    }

    @Test
    void trimsATitleThatIgnoresTheLengthAskedFor() {
        String largo = "Un título larguísimo que el modelo devolvió ignorando la regla de brevedad";

        String limpio = GeneratedResourcePolicy.cleanTitle(largo);

        assertThat(limpio).hasSizeLessThanOrEqualTo(
                GeneratedResourcePolicy.MAX_GENERATED_TITLE_LENGTH);
        assertThat(limpio).doesNotEndWith(" ");
    }
}
