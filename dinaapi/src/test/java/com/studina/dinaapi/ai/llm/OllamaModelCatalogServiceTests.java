package com.studina.dinaapi.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;

class OllamaModelCatalogServiceTests {

    private HttpServer server;
    private OllamaModelCatalogService service;
    private String responseBody;
    private int status;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        responseBody = "{\"models\":[]}";
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            requests.incrementAndGet();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        service = new OllamaModelCatalogService("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void readsInstalledNamesIncludingTagsAndRemovesDuplicates() {
        responseBody = """
                {"models":[
                  {"name":"qwen2.5:7b","model":"qwen2.5:7b","size":123,"details":{"family":"qwen2"}},
                  {"name":"llama3.2:latest"},
                  {"name":"qwen2.5:7b"}
                ]}
                """;

        assertThat(service.installedModels()).containsExactly("llama3.2:latest", "qwen2.5:7b");
        assertThat(requests).hasValue(1);
    }

    @Test
    void supportsTheModelFieldWhenNameIsMissing() {
        responseBody = "{\"models\":[{\"model\":\"llama3.2:1b\"}]}";
        assertThat(service.installedModels()).containsExactly("llama3.2:1b");
    }

    @Test
    void anEmptyInstallationReturnsAnEmptyList() {
        assertThat(service.installedModels()).isEmpty();
    }

    @Test
    void rejectsAResponseWithoutAModelListInsteadOfReportingAnEmptyInstallation() {
        responseBody = "{\"error\":\"unexpected response\"}";
        assertThatThrownBy(service::installedModels).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedModelEntries() {
        responseBody = "{\"models\":[{\"size\":123}]}";
        assertThatThrownBy(service::installedModels).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnavailableServerIsNotRetried() {
        status = 503;
        assertThatThrownBy(service::installedModels).isInstanceOf(HttpServerErrorException.class);
        assertThat(requests).hasValue(1);
    }
}
