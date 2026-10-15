package com.studina.dinaapi.ai.rag;

/**
 * A study resource produced by the model: it belongs to exactly one topic and receives its
 * identity from the server, never from the model.
 *
 * <p>Flashcards, quiz questions and true/false statements differ in their content but follow the
 * same rules for topic assignment and identification, which {@link GeneratedResourcePolicy}
 * applies to all of them.
 */
interface GeneratedResource<T> {

    String topicId();

    /** Same resource with the server-assigned id. */
    T identified(String id);
}
