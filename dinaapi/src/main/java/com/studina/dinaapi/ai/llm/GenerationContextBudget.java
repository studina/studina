package com.studina.dinaapi.ai.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Conservative local limits, without assuming that a cloud tokenizer matches the local model. */
public final class GenerationContextBudget {

    public static final int CONTEXT_TOKENS = 4096;
    // UTF-8 bytes are a conservative proxy, not a model-independent token count.
    // The template allowance is also an estimate. Leave headroom for the chat
    // template and the beginning of the response; verify actual token usage manually.
    public static final int MAX_INPUT_BYTES = 3072;
    public static final int TEMPLATE_RESERVE_BYTES = 128;
    // A fixed 3072-token cutoff cannot accommodate the largest quiz/rubric payloads.
    // This separate ceiling bounds generation work, not the model's total memory use.
    // Long outputs may shift context. Maximum-size generations still need real-model validation.
    public static final int MAX_OUTPUT_TOKENS = 16384;
    private static final int MIN_MATERIAL_BYTES = 256;

    private GenerationContextBudget() {
    }

    public static String fit(String systemPrompt, Function<String, String> userPrompt, List<String> passages) {
        int available = MAX_INPUT_BYTES - TEMPLATE_RESERVE_BYTES
                - utf8Length(systemPrompt) - utf8Length(userPrompt.apply(""));
        if (available < MIN_MATERIAL_BYTES) {
            throw new IllegalArgumentException(
                    "Las instrucciones y los temas exceden el presupuesto de contexto de Ollama. "
                            + "Acortá las instrucciones o seleccioná menos archivos");
        }
        List<String> usable = passages.stream().filter(value -> value != null && !value.isBlank()).toList();
        if (usable.isEmpty()) {
            throw new IllegalArgumentException("No encontré texto utilizable en los documentos seleccionados");
        }
        // Give every retrieved passage a share before extending earlier passages with spare space.
        // Selection and retrieval order stay unchanged; only passage prefixes are shortened.
        int materialBudget = Math.max(0, available - (usable.size() - 1) * utf8Length("\n\n"));
        int share = materialBudget / usable.size();
        List<String> prefixes = new ArrayList<>();
        int remaining = materialBudget;
        for (String passage : usable) {
            String prefix = prefixWithinBytes(passage, share);
            prefixes.add(prefix);
            remaining -= utf8Length(prefix);
        }
        for (int index = 0; index < usable.size() && remaining > 0; index++) {
            int previousBytes = utf8Length(prefixes.get(index));
            String extended = prefixWithinBytes(usable.get(index), previousBytes + remaining);
            prefixes.set(index, extended);
            remaining -= utf8Length(extended) - previousBytes;
        }
        String result = String.join("\n\n", prefixes.stream().filter(value -> !value.isEmpty()).toList());
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No encontré texto utilizable en los documentos seleccionados");
        }
        return result;
    }

    public static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String prefixWithinBytes(String value, int limit) {
        int index = 0;
        int used = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int next = index + Character.charCount(codePoint);
            int size = utf8Length(value.substring(index, next));
            if (used + size > limit) {
                break;
            }
            used += size;
            index = next;
        }
        return value.substring(0, index);
    }
}
