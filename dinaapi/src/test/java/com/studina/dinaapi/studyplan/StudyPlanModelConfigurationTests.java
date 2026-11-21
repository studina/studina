package com.studina.dinaapi.studyplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class StudyPlanModelConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                var sources = context.getEnvironment().getPropertySources();
                sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withBean(ChatClient.Builder.class, () -> {
                var builder = mock(ChatClient.Builder.class);
                when(builder.build()).thenReturn(mock(ChatClient.class));
                return builder;
            })
            .withBean(StudyPlanValidator.class, StudyPlanValidator::new)
            .withBean(GeminiStudyPlanGenerator.class)
            .withPropertyValues("ai.chat.model=gemini-3.5-flash-lite");

    @Test
    void startsWithTheProviderConfigurationWithoutRequiringTheLegacyVariable() {
        assertModel(runner, "gemini-3.5-flash-lite");
    }

    @Test
    void preservesAnExplicitLegacyGeminiModel() {
        assertModel(runner.withPropertyValues("GEMINI_MODEL=gemini-3.5-flash"),
                "gemini-3.5-flash");
    }

    @Test
    void allowsASeparateStudyPlanModelToOverrideTheSharedAndLegacyModels() {
        assertModel(runner.withPropertyValues(
                "GEMINI_MODEL=gemini-3.5-flash", "GEMINI_STUDY_PLAN_MODEL=gemini-3.6-flash"),
                "gemini-3.6-flash");
    }

    private void assertModel(ApplicationContextRunner configured, String expected) {
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(
                    context.getBean(GeminiStudyPlanGenerator.class), "configuredModel"))
                    .isEqualTo(expected);
        });
    }
}
