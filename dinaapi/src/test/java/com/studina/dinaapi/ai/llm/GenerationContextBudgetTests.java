package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class GenerationContextBudgetTests {

    @Test
    void preservesShortPassagesAndLeavesInstructionsAndTopicsUntouched() {
        String system = "Generá preguntas únicamente desde el material.";
        String suffix = "\nTemas: [{\"id\":\"topic-a\",\"name\":\"Álgebra\"}]"
                + "\nIndicaciones: compará los conceptos sin agregar fuentes.";
        Function<String, String> user = context -> "Contexto:\n" + context + suffix;

        String context = GenerationContextBudget.fit(system, user,
                List.of("Primer fragmento completo.", "Segundo fragmento completo."));

        assertThat(context).isEqualTo("Primer fragmento completo.\n\nSegundo fragmento completo.");
        assertThat(user.apply(context)).isEqualTo("Contexto:\n" + context + suffix);
        assertWithinBudget(system, user.apply(context));
    }

    @Test
    void boundsUtf8InputWithoutSplittingAccentsOrSupplementaryCodePoints() {
        String system = "Generá una respuesta estructurada en español.";
        String material = "á😊漢".repeat(1000);
        String suffix = "\nTemas válidos: topic-a\nInstrucciones: conservá estas reglas.";
        Function<String, String> user = context -> "Contexto:\n" + context + suffix;

        String context = GenerationContextBudget.fit(system, user, List.of(material));

        assertThat(context).isNotEmpty().hasSizeLessThan(material.length());
        assertThat(material).startsWith(context);
        assertThat(new String(context.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(context);
        assertThat(user.apply(context)).startsWith("Contexto:\n").endsWith(suffix);
        assertWithinBudget(system, user.apply(context));
    }

    @Test
    void preservesRetrievalOrderAndIncludesLaterPassagesWhenALongPassageIsTruncated() {
        String first = "Fragmento prioritario completo.";
        String second = "Segundo fragmento. ".repeat(500);

        String context = GenerationContextBudget.fit("Reglas", value -> "Contexto:\n" + value,
                List.of(first, second, "Tercer fragmento"));

        assertThat(context).startsWith(first + "\n\nSegundo fragmento.");
        assertThat(context).endsWith("\n\nTercer fragmento");
        assertWithinBudget("Reglas", "Contexto:\n" + context);
    }

    @Test
    void sharesTheBudgetAcrossLongPassagesInsteadOfConsumingOnlyTheFirst() {
        String context = GenerationContextBudget.fit("Reglas", value -> "Contexto:\n" + value,
                List.of("A".repeat(6000), "B".repeat(6000), "C".repeat(6000)));

        String[] parts = context.split("\n\n");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).matches("A+").hasSizeGreaterThan(500);
        assertThat(parts[1]).matches("B+").hasSizeGreaterThan(500);
        assertThat(parts[2]).matches("C+").hasSizeGreaterThan(500);
        assertThat(parts[1].length()).isEqualTo(parts[2].length());
        assertWithinBudget("Reglas", "Contexto:\n" + context);
    }

    @Test
    void reusesUnusedSpaceFromAShortLaterPassageWithoutRemovingOtherPassages() {
        String context = GenerationContextBudget.fit("Reglas", Function.identity(),
                List.of("A".repeat(6000), "B".repeat(6000), "Fin"));

        String[] parts = context.split("\n\n");
        assertThat(parts).hasSize(3);
        assertThat(parts[0].length()).isGreaterThan(parts[1].length());
        assertThat(parts[2]).isEqualTo("Fin");
        assertWithinBudget("Reglas", context);
    }

    @Test
    void rejectsFixedInstructionsThatLeaveLessThanTheMinimumMaterialBudget() {
        String system = "s".repeat(GenerationContextBudget.MAX_INPUT_BYTES
                - GenerationContextBudget.TEMPLATE_RESERVE_BYTES - 255);

        assertThatThrownBy(() -> GenerationContextBudget.fit(system, Function.identity(), List.of("Material")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("presupuesto de contexto");
    }

    @Test
    void allowsTheMinimumMaterialBudgetAtTheBoundary() {
        String system = "s".repeat(GenerationContextBudget.MAX_INPUT_BYTES
                - GenerationContextBudget.TEMPLATE_RESERVE_BYTES - 256);

        String context = GenerationContextBudget.fit(system, Function.identity(), List.of("m".repeat(500)));

        assertThat(context).hasSize(256);
        assertWithinBudget(system, context);
    }

    @Test
    void rejectsOversizedTopicsWithoutExposingTheirContents() {
        String privateTopic = "TOPIC_CONTENT_SHOULD_NOT_BE_LOGGED_".repeat(150);

        assertThatThrownBy(() -> GenerationContextBudget.fit("Reglas",
                value -> "Contexto:\n" + value + "\nTemas:" + privateTopic, List.of("Material")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("presupuesto de contexto")
                .hasMessageNotContaining("TOPIC_CONTENT_SHOULD_NOT_BE_LOGGED_")
                .hasMessageNotContaining("Material");
    }

    private static void assertWithinBudget(String system, String user) {
        int bytes = system.getBytes(StandardCharsets.UTF_8).length
                + user.getBytes(StandardCharsets.UTF_8).length
                + GenerationContextBudget.TEMPLATE_RESERVE_BYTES;
        assertThat(bytes).isLessThanOrEqualTo(GenerationContextBudget.MAX_INPUT_BYTES);
    }
}
