package com.studina.dinaapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class WebConfigTests {

    @Test
    void configuredOriginCanPatchButAnotherOriginIsRejected() throws Exception {
        try (var context = context(Map.of("app.cors.allowed-origins", "https://studina.example"))) {
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(options("/probe").header("Origin", "https://studina.example")
                            .header("Access-Control-Request-Method", "PATCH"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "https://studina.example"));
            mvc.perform(options("/probe").header("Origin", "https://another.example")
                            .header("Access-Control-Request-Method", "PATCH"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void localDevelopmentOriginRemainsAvailableByDefault() throws Exception {
        try (var context = context(Map.of())) {
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(options("/probe").header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "PATCH"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }
    }

    private AnnotationConfigWebApplicationContext context(Map<String, Object> properties) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(MvcConfiguration.class, WebConfig.class);
        context.refresh();
        return context;
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        @PatchMapping("/probe")
        String patch() {
            return "ok";
        }
    }
}
