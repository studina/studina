package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class TopicClassificationResolverTests {

    private static final String SUBJECT_ID = "00000000-0000-0000-0000-000000000010";
    private static final Topic EXISTING_TOPIC =
            new Topic("00000000-0000-0000-0000-000000000001", SUBJECT_ID,
                    "Consistencia eventual/fuerte", "Modelos de consistencia.");

    @Test
    void acceptsExistingTopicsAndNewTopicsInTheSameClassification() {
        TopicClassification classification = new TopicClassification(
                List.of("00000000-0000-0000-0000-000000000001"),
                List.of(new NewTopicProposal("Elección de líder", "Selección del nodo coordinador."))
        );

        TopicClassificationResolver.Resolution result = TopicClassificationResolver.resolve(
                SUBJECT_ID,
                List.of(EXISTING_TOPIC),
                classification
        );

        assertThat(result.existingTopics()).containsExactly(EXISTING_TOPIC);
        assertThat(result.newTopics()).containsExactly(
                new NewTopicProposal("Elección de líder", "Selección del nodo coordinador."));
    }

    @Test
    void rejectsAnIdThatIsNotInTheSubjectCatalog() {
        TopicClassification classification = new TopicClassification(
                List.of("00000000-0000-0000-0000-000000000099"), List.of());

        assertThatThrownBy(() -> TopicClassificationResolver.resolve(
                SUBJECT_ID,
                List.of(EXISTING_TOPIC),
                classification
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inexistente o perteneciente a otra materia");
    }

    @Test
    void rejectsTopicsFromAnotherSubjectEvenWhenTheIdIsInTheCatalog() {
        Topic otherSubjectTopic = new Topic("00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000020", "Consistencia", "Otro catálogo.");

        assertThatThrownBy(() -> TopicClassificationResolver.resolve(
                SUBJECT_ID,
                List.of(otherSubjectTopic),
                new TopicClassification(List.of("00000000-0000-0000-0000-000000000001"), List.of())
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsNewTopicProposalsToThree() {
        TopicClassification tooManyNewTopics = new TopicClassification(
                List.of(),
                List.of(
                        new NewTopicProposal("A", ""),
                        new NewTopicProposal("B", ""),
                        new NewTopicProposal("C", ""),
                        new NewTopicProposal("D", "")
                )
        );

        assertThatThrownBy(() -> TopicClassificationResolver.resolve(
                SUBJECT_ID,
                List.of(),
                tooManyNewTopics
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("más de tres");
    }

    @Test
    void requiresAtLeastOneExistingOrNewTopic() {
        assertThatThrownBy(() -> TopicClassificationResolver.resolve(
                SUBJECT_ID,
                List.of(),
                new TopicClassification(List.of(), List.of())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no asoció ni propuso");
    }
}