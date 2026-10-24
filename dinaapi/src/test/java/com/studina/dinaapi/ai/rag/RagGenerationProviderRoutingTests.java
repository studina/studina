package com.studina.dinaapi.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.studina.dinaapi.ai.llm.LlmClient;
import com.studina.dinaapi.ai.llm.LlmRequest;
import com.studina.dinaapi.ai.llm.LlmResponse;
import com.studina.dinaapi.ai.llm.LlmUseCase;
import com.studina.dinaapi.ai.model_armor.ModelArmorService;
import com.studina.dinaapi.auth.DirectusAuthorizationService;
import com.studina.dinaapi.directus.OpenQuestionSetService;
import com.studina.dinaapi.directus.TrueFalseSetService;
import com.studina.dinaapi.learning.Topic;
import com.studina.dinaapi.learning.TopicCatalogService;

class RagGenerationProviderRoutingTests {

    private static final String AUTHORIZATION = "Bearer user-token";
    private static final String USER_ID = "user-1";
    private static final String SUBJECT_ID = "subject-1";
    private static final String INSTRUCTIONS = "Priorizá el control de congestión";
    private static final String CONTEXT = "TCP reduce su ventana cuando detecta congestión.";
    private static final List<String> FILE_IDS = List.of("file-1");
    private static final List<Topic> TOPICS = List.of(
            new Topic("topic-1", SUBJECT_ID, "TCP", "Control de congestión"));

    private LlmClient llmClient;
    private DirectusAuthorizationService authorizationService;
    private TrueFalseSetService trueFalseSetService;
    private OpenQuestionSetService openQuestionSetService;
    private RagController controller;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(CONTEXT)));
        RagQueryService queryService = new RagQueryService(
                mock(ModelArmorService.class), llmClient, vectorStore);
        authorizationService = mock(DirectusAuthorizationService.class);
        when(authorizationService.requireUserId(AUTHORIZATION)).thenReturn(USER_ID);
        TopicCatalogService topicCatalogService = mock(TopicCatalogService.class);
        when(topicCatalogService.listForFilesOrSubjectFallback(SUBJECT_ID, FILE_IDS)).thenReturn(TOPICS);
        trueFalseSetService = mock(TrueFalseSetService.class);
        openQuestionSetService = mock(OpenQuestionSetService.class);
        controller = new RagController(
                vectorStore, null, queryService, authorizationService, null, null, null, null,
                trueFalseSetService, openQuestionSetService, topicCatalogService, null, null, null);
    }

    @Test
    void trueFalseGenerationUsesTheAuthenticatedUsersGenerationModel() {
        String statements = IntStream.range(0, 5)
                .mapToObj(index -> """
                        {"statement":"Afirmación %d","isTrue":true,"explanation":"Según el apunte","topic_id":"topic-1"}
                        """.formatted(index))
                .collect(Collectors.joining(","));
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
                "{\"title\":\"Control de congestión\",\"statements\":[" + statements + "]}",
                "ollama", "user-generation-model"));
        when(trueFalseSetService.create(anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(Map.of("id", "true-false-set"));

        ResponseEntity<?> response = controller.generateTrueFalse(new TrueFalseGenerationRequest(
                SUBJECT_ID, "Mi repaso", INSTRUCTIONS, FILE_IDS, 5, "medium"), AUTHORIZATION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("id", "true-false-set"));
        LlmRequest request = generatedRequest();
        assertThat(request.systemPrompt()).contains("verdadero o falso", "exactamente 5 enunciados");
        verify(trueFalseSetService).create(eq(SUBJECT_ID), eq("Mi repaso"), eq(INSTRUCTIONS),
                eq("medium"), eq(FILE_IDS), argThat(batch -> batch.size() == 5));
    }

    @Test
    void openQuestionGenerationUsesTheAuthenticatedUsersGenerationModel() {
        String questions = IntStream.range(0, 3)
                .mapToObj(index -> """
                        {"question":"¿Por qué se reduce la ventana %d?","reference_answer":"Para reducir la carga",
                         "key_points":["congestión","menor carga"],"topic_id":"topic-1"}
                        """.formatted(index))
                .collect(Collectors.joining(","));
        when(llmClient.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
                "{\"title\":\"Control de congestión\",\"questions\":[" + questions + "]}",
                "openai", "user-generation-model"));
        when(openQuestionSetService.create(anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(Map.of("id", "open-question-set"));

        ResponseEntity<?> response = controller.generateOpenQuestions(new OpenQuestionGenerationRequest(
                SUBJECT_ID, "Mi repaso", INSTRUCTIONS, FILE_IDS, 3, "medium"), AUTHORIZATION);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("id", "open-question-set"));
        LlmRequest request = generatedRequest();
        assertThat(request.systemPrompt()).contains("preguntas de", "exactamente 3 preguntas", "reference_answer");
        verify(openQuestionSetService).create(eq(SUBJECT_ID), eq("Mi repaso"), eq(INSTRUCTIONS),
                eq("medium"), eq(FILE_IDS), argThat(batch -> batch.size() == 3));
    }

    private LlmRequest generatedRequest() {
        verify(authorizationService).requireSubjectOwnership(USER_ID, SUBJECT_ID);
        verify(authorizationService).requireFileOwnership(USER_ID, FILE_IDS);
        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).generate(request.capture());
        assertThat(request.getValue().userId()).isEqualTo(USER_ID);
        assertThat(request.getValue().useCase()).isEqualTo(LlmUseCase.GENERATION);
        assertThat(request.getValue().userPrompt()).contains(CONTEXT, INSTRUCTIONS, "topic-1");
        return request.getValue();
    }
}
