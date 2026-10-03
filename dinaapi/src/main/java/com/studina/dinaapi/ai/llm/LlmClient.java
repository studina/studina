package com.studina.dinaapi.ai.llm;

/**
 * Puerto de salida para generar texto sin acoplar la aplicación a un proveedor concreto.
 */
public interface LlmClient {

    LlmResponse generate(LlmRequest request);

    LlmModel model();

    default LlmModel model(LlmUseCase useCase) {
        return model();
    }
}
