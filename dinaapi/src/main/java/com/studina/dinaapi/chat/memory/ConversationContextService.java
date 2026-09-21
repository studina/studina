package com.studina.dinaapi.chat.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.studina.dinaapi.chat.ChatConversationService;
import com.studina.dinaapi.chat.ChatMessageService;

@Service
public class ConversationContextService {

    private static final int RECENT_CONTEXT_TOKEN_BUDGET = 4000;

    private static final int COMPACTION_TRIGGER_TOKENS = 6000;

    private static final int MIN_RECENT_MESSAGES = 6;

    private final ChatClient chatClient;
    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;

    @Value("${GEMINI_MODEL}")
    private String configuredModel;

    public ConversationContextService(
            ChatClient.Builder builder,
            ChatConversationService conversationService,
            ChatMessageService messageService
    ) {
        this.chatClient = builder.build();
        this.conversationService = conversationService;
        this.messageService = messageService;
    }

    public ConversationContext prepareContext(
            Map<String, Object> conversation
    ) {

        if (conversation == null) {
            return ConversationContext.empty();
        }

        String conversationId
                = valueAsString(
                        conversation.get("id")
                );

        String currentSummary
                = valueAsString(
                        conversation.get("summary")
                );

        String summaryUntil
                = valueAsString(
                        conversation.get("summary_until")
                );

        /*
         * Solamente obtenemos los mensajes que todavía
         * no están contenidos dentro del summary.
         */
        List<Map<String, Object>> unsummarizedMessages
                = messageService.listUnsummarizedMessages(
                        conversationId,
                        summaryUntil
                );

        if (unsummarizedMessages.isEmpty()) {
            return new ConversationContext(
                    currentSummary,
                    List.of()
            );
        }

        int tokenEstimate
                = approximateTokens(
                        unsummarizedMessages
                );

        /*
         * Si todavía es una conversación razonablemente
         * corta, mandamos esos mensajes completos.
         */
        if (tokenEstimate <= COMPACTION_TRIGGER_TOKENS) {
            return new ConversationContext(
                    currentSummary,
                    toAiMessages(
                            unsummarizedMessages
                    )
            );
        }

        /*
         * Si llegamos acá, ya tenemos demasiado historial.
         *
         * Separamos:
         *
         * mensajes viejos -> summary
         * mensajes recientes -> completos
         */
        int keepFrom
                = findRecentMessagesStart(
                        unsummarizedMessages
                );

        /*
         * Por seguridad, si no encontramos una parte
         * razonable para resumir, dejamos todo como está.
         */
        if (keepFrom <= 0) {
            return new ConversationContext(
                    currentSummary,
                    toAiMessages(
                            unsummarizedMessages
                    )
            );
        }

        List<Map<String, Object>> messagesToSummarize
                = new ArrayList<>(
                        unsummarizedMessages.subList(
                                0,
                                keepFrom
                        )
                );

        List<Map<String, Object>> recentMessages
                = new ArrayList<>(
                        unsummarizedMessages.subList(
                                keepFrom,
                                unsummarizedMessages.size()
                        )
                );

        /*
         * Intentamos generar un resumen nuevo.
         *
         * Si falla, NO actualizamos summary_until.
         * Así nunca perdemos contexto.
         */
        String updatedSummary;

        try {
            updatedSummary
                    = summarize(
                            currentSummary,
                            messagesToSummarize
                    );
        } catch (Exception exception) {

            return new ConversationContext(
                    currentSummary,
                    toAiMessages(
                            unsummarizedMessages
                    )
            );
        }

        if (updatedSummary == null
                || updatedSummary.isBlank()) {
            return new ConversationContext(
                    currentSummary,
                    toAiMessages(
                            unsummarizedMessages
                    )
            );
        }

        /*
         * El último mensaje resumido determina
         * hasta qué fecha llega nuestra memoria comprimida.
         */
        Map<String, Object> lastSummarizedMessage
                = messagesToSummarize.get(
                        messagesToSummarize.size() - 1
                );

        String newSummaryUntil
                = valueAsString(
                        lastSummarizedMessage.get(
                                "date_created"
                        )
                );

        /*
         * Guardamos la nueva memoria en Directus.
         */
        conversationService.updateSummary(
                conversationId,
                updatedSummary,
                newSummaryUntil
        );

        return new ConversationContext(
                updatedSummary,
                toAiMessages(
                        recentMessages
                )
        );
    }

    private int findRecentMessagesStart(
            List<Map<String, Object>> messages
    ) {

        int approximateTokens = 0;
        int keptMessages = 0;

        int index = messages.size();

        /*
         * Empezamos desde el mensaje más reciente
         * y vamos hacia atrás.
         */
        while (index > 0) {

            Map<String, Object> message
                    = messages.get(index - 1);

            int messageTokens
                    = approximateTokens(
                            valueAsString(
                                    message.get("content")
                            )
                    );

            /*
             * Una vez que conservamos al menos algunos
             * mensajes recientes, respetamos el presupuesto.
             */
            if (keptMessages >= MIN_RECENT_MESSAGES
                    && approximateTokens
                    + messageTokens
                    > RECENT_CONTEXT_TOKEN_BUDGET) {
                break;
            }

            approximateTokens += messageTokens;
            keptMessages++;

            index--;
        }

        /*
         * Intentamos que el historial reciente empiece
         * en un mensaje del usuario.
         *
         * Así evitamos algo como:
         *
         * assistant: ...
         * user: ...
         *
         * sin saber qué pregunta produjo esa respuesta.
         */
        while (index > 0
                && !"user".equals(
                        valueAsString(
                                messages
                                        .get(index)
                                        .get("role")
                        )
                )) {
            index--;
        }

        return index;
    }

    private String summarize(
            String previousSummary,
            List<Map<String, Object>> messages
    ) {

        String transcript
                = buildTranscript(messages);

        return chatClient.prompt()
                .system("""
                        Sos un componente interno de memoria conversacional.

                        Tu tarea es mantener un resumen fiel y compacto
                        de una conversación entre un estudiante y una
                        asistente educativa.

                        Conservá especialmente:
                        - conceptos y temas estudiados;
                        - definiciones importantes;
                        - decisiones o acuerdos tomados;
                        - referencias que puedan aparecer más adelante;
                        - términos definidos por el estudiante;
                        - dudas importantes y su resolución;
                        - contexto necesario para entender referencias futuras.

                        Eliminá:
                        - saludos;
                        - repeticiones;
                        - frases de relleno;
                        - detalles que no tengan utilidad futura.

                        No inventes información.
                        No respondas al estudiante.
                        Respondé únicamente con el resumen actualizado.
                        Intentá mantenerlo por debajo de 700 palabras.
                        """)
                .user("""
                        Resumen existente:

                        %s

                        Nuevos mensajes que deben incorporarse:

                        %s
                        """.formatted(
                        previousSummary == null
                        || previousSummary.isBlank()
                        ? "No existe un resumen anterior."
                        : previousSummary,
                        transcript
                ))
                .options(
                        GoogleGenAiChatOptions.builder()
                                .model(configuredModel)
                                .temperature(0.1)
                )
                .call()
                .content();
    }

    private String buildTranscript(
            List<Map<String, Object>> messages
    ) {

        StringBuilder builder
                = new StringBuilder();

        for (Map<String, Object> message : messages) {

            String role
                    = valueAsString(
                            message.get("role")
                    );

            String content
                    = valueAsString(
                            message.get("content")
                    );

            if (role == null
                    || content == null
                    || content.isBlank()) {
                continue;
            }

            switch (role) {
                case "assistant" ->
                    builder.append("DINA");
                case "user" ->
                    builder.append("ESTUDIANTE");
                default -> {
                    continue;
                }
            }

            builder
                    .append(":\n")
                    .append(content)
                    .append("\n\n");
        }

        return builder.toString();
    }

    private List<Message> toAiMessages(
            List<Map<String, Object>> messages
    ) {

        List<Message> result
                = new ArrayList<>();

        for (Map<String, Object> message : messages) {

            String role
                    = valueAsString(
                            message.get("role")
                    );

            String content
                    = valueAsString(
                            message.get("content")
                    );

            if (role == null
                    || content == null
                    || content.isBlank()) {
                continue;
            }

            if ("user".equals(role)) {

                result.add(
                        new UserMessage(content)
                );

            } else if ("assistant".equals(role)) {

                result.add(
                        new AssistantMessage(content)
                );
            }
        }

        return result;
    }

    private int approximateTokens(
            List<Map<String, Object>> messages
    ) {

        return messages.stream()
                .map(
                        message
                        -> valueAsString(
                                message.get(
                                        "content"
                                )
                        )
                )
                .mapToInt(
                        this::approximateTokens
                )
                .sum();
    }

    private int approximateTokens(
            String text
    ) {

        if (text == null
                || text.isBlank()) {
            return 0;
        }

        return Math.max(
                1,
                text.length() / 4
        );
    }

    private String valueAsString(
            Object value
    ) {

        return value != null
                ? value.toString()
                : null;
    }
}
