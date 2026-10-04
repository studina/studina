package com.studina.dinaapi.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class TopicCatalogServiceTests {

        private static final String TOPIC_A = "00000000-0000-0000-0000-000000000001";
        private static final String TOPIC_B = "00000000-0000-0000-0000-000000000002";
        private static final String TOPIC_C = "00000000-0000-0000-0000-000000000003";

    private final List<Topic> subjectTopics = List.of(
                        new Topic(TOPIC_A, "subject", "Replicación", ""),
                        new Topic(TOPIC_B, "subject", "Consistencia", ""),
                        new Topic(TOPIC_C, "subject", "Kafka", "")
    );

    @Test
    void limitsGenerationTopicsToTheTopicsAssociatedWithSelectedFiles() {
        List<Topic> selectedTopics = TopicCatalogService.selectTopicsForGeneration(
                subjectTopics,
                Set.of(TOPIC_A, TOPIC_B)
        );

        assertThat(selectedTopics).extracting(Topic::id).containsExactly(TOPIC_A, TOPIC_B);
    }

    @Test
    void usesSubjectCatalogOnlyWhenSelectedFilesHaveNoTopics() {
        List<Topic> selectedTopics = TopicCatalogService.selectTopicsForGeneration(
                subjectTopics,
                Set.of()
        );

        assertThat(selectedTopics).containsExactlyElementsOf(subjectTopics);
    }

    @Test
    void findsAnExistingTopicRegardlessOfCaseAndAccents() {
        Topic existing = TopicCatalogService.findByNormalizedName(
                subjectTopics,
                TopicCatalogService.normalize("  REPLICACION ")
        );

        assertThat(existing).isNotNull();
        assertThat(existing.id()).isEqualTo(TOPIC_A);
    }

    @Test
    void doesNotMatchADifferentTopicName() {
        Topic existing = TopicCatalogService.findByNormalizedName(
                subjectTopics,
                TopicCatalogService.normalize("Replicación activa")
        );

        assertThat(existing).isNull();
    }

    @Test
    void renamingATopicToAVariantOfItsOwnNameIsNotAConflict() {
        Topic other = TopicCatalogService.findOtherByNormalizedName(
                subjectTopics,
                TopicCatalogService.normalize("replicacion"),
                TOPIC_A
        );

        assertThat(other).isNull();
    }

    @Test
    void renamingATopicToAnotherTopicsNameIsAConflict() {
        Topic other = TopicCatalogService.findOtherByNormalizedName(
                subjectTopics,
                TopicCatalogService.normalize("KAFKA"),
                TOPIC_A
        );

        assertThat(other).isNotNull();
        assertThat(other.id()).isEqualTo(TOPIC_C);
    }
}
