package com.studina.dinaapi.ai.llm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

class OllamaModelCatalogControllerTests {

    private DirectusAuthorizationService authorizationService;
    private OllamaModelCatalogService catalogService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authorizationService = mock(DirectusAuthorizationService.class);
        catalogService = mock(OllamaModelCatalogService.class);
        when(authorizationService.requireUserId("Bearer student-token")).thenReturn("student-id");
        mvc = MockMvcBuilders.standaloneSetup(new OllamaModelCatalogController(
                authorizationService, catalogService)).build();
    }

    @Test
    void exposesInstalledModelsToAnAuthenticatedStudent() throws Exception {
        when(catalogService.installedModels()).thenReturn(List.of("llama3.2:latest", "qwen2.5:7b"));
        mvc.perform(get("/ai/models/ollama").header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("ollama"))
                .andExpect(jsonPath("$.models[0]").value("llama3.2:latest"))
                .andExpect(jsonPath("$.models[1]").value("qwen2.5:7b"));
    }

    @Test
    void aStudentWithoutASessionCannotQueryTheServer() throws Exception {
        when(authorizationService.requireUserId(null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        mvc.perform(get("/ai/models/ollama")).andExpect(status().isUnauthorized());
        verifyNoInteractions(catalogService);
    }

    @Test
    void anEmptyInstallationIsNotAnError() throws Exception {
        when(catalogService.installedModels()).thenReturn(List.of());
        mvc.perform(get("/ai/models/ollama").header("Authorization", "Bearer student-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models").isEmpty());
    }

    @Test
    void reportsAnUnavailableServerWithoutExposingItsAddress() throws Exception {
        when(catalogService.installedModels()).thenThrow(new ResourceAccessException("http://private-ollama:11434"));
        mvc.perform(get("/ai/models/ollama").header("Authorization", "Bearer student-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("No se pudo consultar Ollama. Pedile al responsable de Studina "
                        + "que revise el servidor y volvé a actualizar la lista."));
    }

    @Test
    void reportsAnInvalidUpstreamResponse() throws Exception {
        when(catalogService.installedModels()).thenThrow(new IllegalStateException("invalid models"));
        mvc.perform(get("/ai/models/ollama").header("Authorization", "Bearer student-token"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }
}
