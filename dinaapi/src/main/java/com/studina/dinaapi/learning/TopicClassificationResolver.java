package com.studina.dinaapi.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class TopicClassificationResolver {

    private TopicClassificationResolver() {
    }

    public static Resolution resolve(
            String subjectId,
            List<Topic> catalog,
            TopicClassification classification) {
        if (classification == null) {
            throw new IllegalArgumentException("La clasificación de topics está vacía");
        }

        Map<String, Topic> catalogById = catalog.stream()
                .collect(Collectors.toMap(topic -> String.valueOf(topic.id()), topic -> topic));
        LinkedHashMap<String, Topic> existingTopics = new LinkedHashMap<>();
        for (String existingId : classification.existingTopicIds()) {
            Topic existing = catalogById.get(existingId);
            if (existing == null || !subjectId.equals(existing.subjectId())) {
                throw new IllegalArgumentException(
                        "La IA devolvió un topic inexistente o perteneciente a otra materia: " + existingId);
            }
            existingTopics.putIfAbsent(existingId, existing);
        }

        if (classification.newTopics().size() > 3) {
            throw new IllegalArgumentException("La IA propuso más de tres topics nuevos");
        }
        List<NewTopicProposal> newTopics = new ArrayList<>();
        for (NewTopicProposal proposal : classification.newTopics()) {
            if (proposal == null || proposal.name() == null || proposal.name().isBlank()) {
                throw new IllegalArgumentException("La IA propuso un topic nuevo sin nombre");
            }
            newTopics.add(proposal);
        }

        if (existingTopics.isEmpty() && newTopics.isEmpty()) {
            throw new IllegalArgumentException("La IA no asoció ni propuso topics para el documento");
        }
        return new Resolution(List.copyOf(existingTopics.values()), List.copyOf(newTopics));
    }

    public record Resolution(List<Topic> existingTopics, List<NewTopicProposal> newTopics) {
    }
}