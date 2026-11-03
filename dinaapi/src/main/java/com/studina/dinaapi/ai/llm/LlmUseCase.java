package com.studina.dinaapi.ai.llm;

/**
 * Tipo de trabajo pedido al modelo. Permite elegir un modelo distinto para
 * tareas rápidas y para generaciones que requieren más calidad.
 */
public enum LlmUseCase {
    CHAT,
    GENERATION,
    CLASSIFICATION,
    SUMMARY
}
