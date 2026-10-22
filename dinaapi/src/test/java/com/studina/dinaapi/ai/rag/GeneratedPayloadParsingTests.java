package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Deserialización de lo que devuelve la IA al generar.
 *
 * <p>El JSON pedido pasó de {@code {"cards":[...]}} a {@code {"title":"...","cards":[...]}}, y
 * el parseo pasó de un mapa tipado a un record. Un modelo no está obligado a respetar el
 * formato: si omite el título o agrega campos de más, las tarjetas tienen que llegar igual,
 * porque son lo único sin lo cual la generación no sirve.
 */
class GeneratedPayloadParsingTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsTheTitleAndTheCards() throws Exception {
        String respuesta = """
                {"title":"Consistencia en sistemas distribuidos",
                 "cards":[{"term":"Consistencia eventual","definition":"Las réplicas convergen.",
                           "topic_id":"11111111-1111-1111-1111-111111111111"}]}
                """;

        RagQueryService.GeneratedFlashcards parsed =
                objectMapper.readValue(respuesta, RagQueryService.GeneratedFlashcards.class);

        assertThat(parsed.title()).isEqualTo("Consistencia en sistemas distribuidos");
        assertThat(parsed.cards()).hasSize(1);
        assertThat(parsed.cards().get(0).term()).isEqualTo("Consistencia eventual");
        assertThat(parsed.cards().get(0).topicId())
                .as("topic_id viaja en snake_case y se mapea por @JsonProperty")
                .isEqualTo("11111111-1111-1111-1111-111111111111");
    }

    @Test
    void stillReadsTheCardsWhenTheModelIgnoresTheTitle() throws Exception {
        // El formato viejo, que es también lo que devuelve un modelo que ignora la regla.
        String respuesta = """
                {"cards":[{"term":"Elección de líder","definition":"Se designa un coordinador.",
                           "topic_id":"22222222-2222-2222-2222-222222222222"}]}
                """;

        RagQueryService.GeneratedFlashcards parsed =
                objectMapper.readValue(respuesta, RagQueryService.GeneratedFlashcards.class);

        assertThat(parsed.title()).isNull();
        assertThat(parsed.cards())
                .as("sin título la generación sigue siendo válida: el nombre tiene fallback")
                .hasSize(1);
    }

    @Test
    void acceptsExtraFieldsAtTheTopLevelButNotInsideACard() throws Exception {
        // Arriba el wrapper es tolerante: que el modelo agregue un resumen no invalida nada.
        String conRuidoArriba = """
                {"title":"Comercio internacional","resumen":"algo que nadie pidió",
                 "cards":[{"term":"Arancel","definition":"Impuesto a la importación.",
                           "topic_id":"33333333-3333-3333-3333-333333333333"}]}
                """;

        RagQueryService.GeneratedFlashcards parsed =
                objectMapper.readValue(conRuidoArriba, RagQueryService.GeneratedFlashcards.class);
        assertThat(parsed.cards()).hasSize(1);
        assertThat(parsed.title()).isEqualTo("Comercio internacional");

        // Adentro de una tarjeta, en cambio, el rechazo es deliberado: es el mismo
        // mecanismo que detecta el payload viejo con "topic_ids" y evita crear tarjetas
        // sin tema en silencio (ver GeneratedResourcePolicyTests). Preferimos fallar la
        // generación antes que guardar algo mal formado.
        String conRuidoEnLaTarjeta = """
                {"cards":[{"term":"Arancel","definition":"Impuesto.",
                           "topic_id":"33333333-3333-3333-3333-333333333333","dificultad":"alta"}]}
                """;

        assertThatThrownBy(() -> objectMapper.readValue(
                conRuidoEnLaTarjeta, RagQueryService.GeneratedFlashcards.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void readsAQuizTheSameWay() throws Exception {
        String respuesta = """
                {"title":"Normativa aduanera",
                 "questions":[{"question":"¿Qué es un arancel?",
                               "options":[{"id":"a","text":"Un impuesto"},{"id":"b","text":"Un permiso"}],
                               "correctOptionId":"a","explanation":"Grava la importación.",
                               "topic_id":"44444444-4444-4444-4444-444444444444"}]}
                """;

        RagQueryService.GeneratedQuiz parsed =
                objectMapper.readValue(respuesta, RagQueryService.GeneratedQuiz.class);

        assertThat(parsed.title()).isEqualTo("Normativa aduanera");
        assertThat(parsed.questions()).hasSize(1);
        assertThat(parsed.questions().get(0).options()).hasSize(2);
    }

    @Test
    void readsATrueFalseSetTheSameWay() throws Exception {
        String respuesta = """
                {"title":"Protocolos de transporte",
                 "statements":[{"statement":"UDP garantiza el orden de entrega.","isTrue":false,
                                "explanation":"UDP no garantiza orden; TCP sí.",
                                "topic_id":"55555555-5555-5555-5555-555555555555"}]}
                """;

        RagQueryService.GeneratedTrueFalse parsed =
                objectMapper.readValue(respuesta, RagQueryService.GeneratedTrueFalse.class);

        assertThat(parsed.title()).isEqualTo("Protocolos de transporte");
        assertThat(parsed.statements()).hasSize(1);
        assertThat(parsed.statements().get(0).isTrue()).isFalse();
        assertThat(parsed.statements().get(0).topicId())
                .isEqualTo("55555555-5555-5555-5555-555555555555");
    }

    @Test
    void readsAnOpenQuestionSetWithItsRubric() throws Exception {
        String respuesta = """
                {"title":"Control de congestión",
                 "questions":[{"question":"¿Por qué TCP reduce la ventana ante una pérdida?",
                               "reference_answer":"Porque interpreta la pérdida como congestión.",
                               "key_points":["la pérdida indica congestión","reducir la ventana baja la carga"],
                               "topic_id":"66666666-6666-6666-6666-666666666666"}]}
                """;

        RagQueryService.GeneratedOpenQuestions parsed =
                objectMapper.readValue(respuesta, RagQueryService.GeneratedOpenQuestions.class);

        assertThat(parsed.title()).isEqualTo("Control de congestión");
        assertThat(parsed.questions()).hasSize(1);
        assertThat(parsed.questions().get(0).referenceAnswer())
                .isEqualTo("Porque interpreta la pérdida como congestión.");
        assertThat(parsed.questions().get(0).keyPoints()).hasSize(2);
        assertThat(parsed.questions().get(0).topicId())
                .isEqualTo("66666666-6666-6666-6666-666666666666");
    }
}
