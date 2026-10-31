package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import static org.mockito.ArgumentMatchers.any;

import com.studina.dinaapi.ai.rag.RagQueryService;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.learning.Topic;

import com.studina.dinaapi.ai.settings.UserLlmRuntimeSettings;
import com.studina.dinaapi.ai.settings.UserLlmSettingsService;

/**
 * Opt-in integration with an already running, isolated local Ollama instance.
 *
 * <p>Set OLLAMA_TEST_URL and OLLAMA_TEST_MODEL after downloading the model to that
 * instance. These tests do not start services, download models, access Directus or
 * pgvector, or use API keys. Only loopback HTTP endpoints are accepted.
 *
 * <p>From dinaapi:
 * OLLAMA_TEST_URL=http://127.0.0.1:11434 OLLAMA_TEST_MODEL=llama3.2:1b
 * ./gradlew --no-daemon --max-workers=2 --no-parallel
 * -Dorg.gradle.jvmargs="-Xmx1g -XX:MaxMetaspaceSize=384m"
 * test --tests '*OllamaLlmIntegrationTests.generatesMinimumStudyResources'
 *
 * <p>These tests perform real inference and are not memory-safety checks. Check available
 * memory and model requirements before running them manually. Maximum batches and the
 * concurrent test require their own explicit opt-ins; do not run the whole class as a smoke test.
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_TEST_URL", matches = ".+")
@Timeout(660)
class OllamaLlmIntegrationTests {

    private String model;
    private LlmProviderClientFactory factory;

    private static final String RESOURCE_USER = "ollama-resource-test";
    private static final String RESOURCE_TOPIC = "11111111-1111-1111-1111-111111111111";
    private static final String SYNTHETIC_MATERIAL = """
            Manual ficticio de la Biblioteca Aurora: reglas de este sistema de estudio.
            1. El catálogo describe el título, el autor y el tema de cada obra.
            2. El inventario registra cada ejemplar físico mediante un código único.
            3. Un préstamo vincula un ejemplar disponible con una persona registrada.
            4. La devolución cierra el préstamo y deja el ejemplar disponible.
            5. La renovación extiende siete días el plazo si no hay reservas pendientes.
            6. La reserva establece un turno para retirar una obra actualmente prestada.
            7. El retiro de una reserva debe hacerse dentro de dos días de la notificación.
            8. La credencial identifica a la persona; no identifica al ejemplar.
            9. El sector de referencia permite consultar obras dentro de la biblioteca.
            10. El depósito conserva obras frágiles en cajas que reducen la exposición a la luz.
            11. La restauración repara daños físicos antes de volver a prestar un ejemplar.
            12. La digitalización crea una copia consultable sin manipular el original.
            13. El índice temático agrupa obras por asunto y facilita buscar conceptos relacionados.
            14. La signatura indica la ubicación del ejemplar en estante y sector.
            15. La auditoría compara inventario y estantes para detectar faltantes.
            16. Una donación aceptada ingresa al inventario después de evaluar su estado.
            17. La baja retira del inventario un ejemplar perdido o irrecuperable.
            18. La sala silenciosa permite lectura individual sin conversaciones.
            19. La sala grupal permite discutir proyectos con una reserva de horario.
            20. El informe mensual cuenta préstamos y devoluciones para planificar el servicio.
            """;

    enum StudyResource {
        FLASHCARDS(5, 20), QUIZ(5, 20), TRUE_FALSE(5, 20), OPEN_QUESTIONS(3, 10);

        final int minimum;
        final int maximum;

        StudyResource(int minimum, int maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
        }
    }

    @ParameterizedTest(name = "minimum {0}")
    @EnumSource(StudyResource.class)
    @Timeout(660)
    void generatesMinimumStudyResources(StudyResource resource) {
        generateAndVerifyStudyResource(resource, resource.minimum, 2);
    }

    @ParameterizedTest(name = "maximum {0}")
    @EnumSource(StudyResource.class)
    @EnabledIfEnvironmentVariable(named = "OLLAMA_TEST_MAX_RESOURCES", matches = "true")
    @Timeout(660)
    void generatesMaximumStudyResources(StudyResource resource) {
        generateAndVerifyStudyResource(resource, resource.maximum, 4);
    }

    private void generateAndVerifyStudyResource(StudyResource resource, int count, int answerCount) {
        UserLlmSettingsService settings = mock(UserLlmSettingsService.class);
        when(settings.runtimeFor(RESOURCE_USER)).thenReturn(Optional.of(
                new UserLlmRuntimeSettings("ollama", "unused-chat-model", model, null, null, null)));
        var client = new SpringAiLlmClient(mock(ChatClient.class), new LlmProperties(), settings, factory);
        var vectors = mock(VectorStore.class);
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(SYNTHETIC_MATERIAL)));
        var service = new RagQueryService(mock(ModelArmorService.class), client, vectors);
        var topics = List.of(new Topic(RESOURCE_TOPIC, "22222222-2222-2222-2222-222222222222",
                "Biblioteca Aurora", "Manual sintético"));
        var fileIds = List.of("33333333-3333-3333-3333-333333333333");
        String instructions = "Cubrí hechos distintos del manual ficticio, usando sólo el contexto.";
        long started = System.nanoTime();
        try {
            switch (resource) {
                case FLASHCARDS -> {
                    var response = service.generateFlashcards(instructions, fileIds, count,
                            "term_definition", "basic", topics, RESOURCE_USER);
                    assertThat(response.title()).isNotBlank();
                    assertThat(response.chunksUsed()).isEqualTo(1);
                    assertThat(response.cards()).hasSize(count).allSatisfy(card -> {
                        assertThat(card.term()).isNotBlank();
                        assertThat(card.definition()).isNotBlank();
                        assertThat(card.topicId()).isEqualTo(RESOURCE_TOPIC);
                    });
                    verifyServerIds(response.cards().stream().map(card -> card.id()).toList(), count);
                }
                case QUIZ -> {
                    var response = service.generateQuiz(instructions, fileIds, count,
                            answerCount, "basic", topics, RESOURCE_USER);
                    assertThat(response.title()).isNotBlank();
                    assertThat(response.chunksUsed()).isEqualTo(1);
                    assertThat(response.questions()).hasSize(count).allSatisfy(question -> {
                        assertThat(question.question()).isNotBlank();
                        assertThat(question.explanation()).isNotBlank();
                        assertThat(question.topicId()).isEqualTo(RESOURCE_TOPIC);
                        assertThat(question.correctOptionId()).isNotBlank();
                        assertThat(question.options()).hasSize(answerCount).allSatisfy(option -> {
                            assertThat(option.id()).isNotBlank();
                            assertThat(option.text()).isNotBlank();
                        });
                        assertThat(question.options().stream().map(option -> option.id()).toList())
                                .doesNotHaveDuplicates().contains(question.correctOptionId());
                    });
                    verifyServerIds(response.questions().stream().map(question -> question.id()).toList(), count);
                }
                case TRUE_FALSE -> {
                    var response = service.generateTrueFalse(instructions, fileIds, count,
                            "basic", topics, RESOURCE_USER);
                    assertThat(response.title()).isNotBlank();
                    assertThat(response.chunksUsed()).isEqualTo(1);
                    assertThat(response.statements()).hasSize(count).allSatisfy(statement -> {
                        assertThat(statement.statement()).isNotBlank();
                        assertThat(statement.isTrue()).isNotNull();
                        assertThat(statement.explanation()).isNotBlank();
                        assertThat(statement.topicId()).isEqualTo(RESOURCE_TOPIC);
                    });
                    verifyServerIds(response.statements().stream().map(statement -> statement.id()).toList(), count);
                }
                case OPEN_QUESTIONS -> {
                    var response = service.generateOpenQuestions(instructions, fileIds, count,
                            "basic", topics, RESOURCE_USER);
                    assertThat(response.title()).isNotBlank();
                    assertThat(response.chunksUsed()).isEqualTo(1);
                    assertThat(response.questions()).hasSize(count).allSatisfy(question -> {
                        assertThat(question.question()).isNotBlank();
                        assertThat(question.referenceAnswer()).isNotBlank();
                        assertThat(question.keyPoints()).hasSizeBetween(2, 5)
                                .allSatisfy(point -> assertThat(point).isNotBlank());
                        assertThat(question.topicId()).isEqualTo(RESOURCE_TOPIC);
                    });
                    verifyServerIds(response.questions().stream().map(question -> question.id()).toList(), count);
                }
            }
        } finally {
            System.out.printf("Ollama real: resource=%s count=%d durationSeconds=%.1f%n",
                    resource, count, (System.nanoTime() - started) / 1_000_000_000.0);
        }
    }

    private static void verifyServerIds(List<String> ids, int count) {
        assertThat(ids).hasSize(count).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(UUID.fromString(id).toString()).isEqualTo(id));
    }

    @Test
    @Timeout(1200)
    @EnabledIfEnvironmentVariable(named = "OLLAMA_TEST_CONCURRENT_RESOURCES", matches = "true")
    void generatesThreeConcurrentFlashcardSetsWithoutDatabasesOrSavingResources() throws Exception {
        UserLlmSettingsService settings = mock(UserLlmSettingsService.class);
        when(settings.runtimeFor("ollama-flashcards-test")).thenReturn(Optional.of(
                new UserLlmRuntimeSettings("ollama", model, model, null, null, null)));
        var client = new SpringAiLlmClient(mock(ChatClient.class), new LlmProperties(), settings, factory);
        var vectors = mock(VectorStore.class);
        String material = """
                La propiedad intelectual protege creaciones e invenciones. Una patente concede
                exclusividad temporal sobre una invención nueva, con actividad inventiva y aplicación
                industrial. A cambio, la invención debe divulgarse. Una marca distingue productos y
                servicios de una empresa y puede renovarse mientras se cumplan los requisitos legales.
                El derecho de autor protege la expresión original de una obra, no sus ideas abstractas.
                La transferencia tecnológica permite compartir conocimientos mediante licencias,
                acuerdos de cooperación o cesiones. Una licencia autoriza usos específicos y mantiene
                la titularidad del concedente; una cesión transfiere la titularidad. Una licencia
                exclusiva limita la autorización de terceros; una no exclusiva admite otros usuarios.
                Las regalías son pagos por el uso autorizado. Los acuerdos deben definir territorio,
                duración, usos permitidos y obligaciones. El secreto empresarial exige preservar la
                confidencialidad; divulgarlo puede hacer perder su protección. La confidencialidad
                puede ayudar antes de solicitar una patente. Patentar implica publicar la invención,
                mientras que el secreto mantiene la información reservada. Una universidad puede
                transferir una tecnología a una empresa para desarrollar aplicaciones productivas.
                """;
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document(material.repeat(8))));
        var service = new RagQueryService(mock(ModelArmorService.class), client, vectors);
        var topics = List.of(new Topic("topic-ip", "subject-test", "Propiedad intelectual", ""));
        var start = new CyclicBarrier(3);
        try (var workers = Executors.newFixedThreadPool(3)) {
            var tasks = java.util.stream.IntStream.range(0, 3).mapToObj(index -> workers.submit(() -> {
                int count = new int[]{5, 6, 10}[index];
                String format = index == 1 ? "question_answer" : "term_definition";
                String difficulty = new String[]{"basic", "intermediate", "advanced"}[index];
                start.await(10, TimeUnit.SECONDS);
                long started = System.nanoTime();
                var response = service.generateFlashcards("", List.of("file-test"), count, format,
                        difficulty, topics, "ollama-flashcards-test");
                System.out.printf("Ollama real: %d tarjetas (%s, %s), %.1f segundos%n",
                        count, difficulty, format, (System.nanoTime() - started) / 1_000_000_000.0);
                assertThat(response.cards()).hasSize(count).allSatisfy(card -> {
                    assertThat(card.term()).isNotBlank();
                    assertThat(card.definition()).isNotBlank();
                    assertThat(card.topicId()).isEqualTo("topic-ip");
                    if (index == 1) {
                        assertThat(card.term()).endsWith("?");
                    }
                });
                return response;
            })).toList();
            for (var task : tasks) {
                task.get(1100, TimeUnit.SECONDS);
            }
        }
    }

    @BeforeEach
    void configureLocalInstance() {
        String baseUrl = System.getenv("OLLAMA_TEST_URL");
        URI endpoint = URI.create(baseUrl);
        assertThat(endpoint.getScheme()).as("Use a local HTTP Ollama endpoint").isEqualTo("http");
        assertThat(endpoint.getHost()).as("Remote providers are excluded from these tests")
                .isIn("localhost", "127.0.0.1", "::1", "[::1]");
        assertThat(endpoint.getUserInfo()).as("Ollama tests do not use credentials").isNull();

        model = System.getenv("OLLAMA_TEST_MODEL");
        assertThat(model).as("OLLAMA_TEST_MODEL must name an already downloaded model").isNotBlank();
        factory = new LlmProviderClientFactory(baseUrl, "http://127.0.0.1:1/v1", 600);
    }

    @Test
    void generatesWithTheServerDefaultAndConversationHistory() {
        LlmProperties properties = new LlmProperties();
        properties.setProvider("ollama");
        properties.setModel(model);
        properties.setTemperature(0.0);
        ChatClient unusedDefaultClient = mock(ChatClient.class);
        UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
        when(settingsService.runtimeFor("ollama-default-integration-user")).thenReturn(Optional.empty());
        SpringAiLlmClient client = new SpringAiLlmClient(
                unusedDefaultClient, properties, settingsService, factory);

        LlmResponse response = client.generate(LlmRequest.of(
                "Follow the user's instructions. Return only the requested code.",
                List.of(new UserMessage("The code to remember is MENTA.")),
                "What code did I give you? Return only that code.")
                .forUser("ollama-default-integration-user"));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.model()).isEqualTo(model);
        assertThat(response.content()).containsIgnoringCase("MENTA");
        verifyNoInteractions(unusedDefaultClient);
    }

    @Test
    void selectsTheUsersProviderAndGenerationModelInsteadOfServerDefaults() {
        UserLlmRuntimeSettings settings = new UserLlmRuntimeSettings(
                "ollama", "unused-chat-model", model, null, null, null);
        UserLlmSettingsService settingsService = mock(UserLlmSettingsService.class);
        when(settingsService.runtimeFor("ollama-integration-user"))
                .thenReturn(Optional.of(settings));
        ChatClient unusedDefaultClient = mock(ChatClient.class);
        SpringAiLlmClient client = new SpringAiLlmClient(
                unusedDefaultClient, new LlmProperties(), settingsService, factory);

        LlmResponse response = client.generate(LlmRequest.of(
                "Follow the user's instructions precisely.",
                "Reply with only the word OK.")
                .forUseCase(LlmUseCase.GENERATION)
                .forUser("ollama-integration-user")
                .withTemperature(0.0));

        assertThat(response.provider()).isEqualTo("ollama");
        assertThat(response.model()).isEqualTo(model);
        assertThat(response.content()).containsIgnoringCase("OK");
        assertThat(client.model(LlmUseCase.GENERATION, "ollama-integration-user"))
                .isEqualTo(new LlmModel("ollama", model));
        verifyNoInteractions(unusedDefaultClient);
    }
}
