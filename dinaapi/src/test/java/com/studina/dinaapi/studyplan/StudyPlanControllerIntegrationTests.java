package com.studina.dinaapi.studyplan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StudyPlanControllerIntegrationTests {

    private GeminiStudyPlanGenerator generator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {

        generator
                = Mockito.mock(
                        GeminiStudyPlanGenerator.class
                );

        mockMvc
                = MockMvcBuilders
                        .standaloneSetup(
                                new StudyPlanController(
                                        generator
                                )
                        )
                        .build();
    }

    @Test
    void generatesAPlanThroughTheHttpContract() throws Exception {
        mockMvc.perform(post("/study-plans/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "startDate": "2026-10-05",
                                  "examDate": "2026-10-07",
                                  "topics": [{"title": "Redes", "estimatedMinutes": 60}],
                                  "availability": [
                                    {"dayOfWeek": "MONDAY", "startTime": "18:00", "endTime": "20:00"},
                                    {"dayOfWeek": "TUESDAY", "startTime": "18:00", "endTime": "20:00"}
                                  ],
                                  "reviewSessions": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canBeScheduled").value(true))
                .andExpect(jsonPath("$.sessions.length()").value(2))
                .andExpect(jsonPath("$.sessions[0].type").value("STUDY"))
                .andExpect(jsonPath("$.sessions[1].type").value("MOCK_EXAM"));
    }

    @Test
    void returnsUnprocessableEntityWhenTheValidRequestDoesNotFit() throws Exception {
        mockMvc.perform(post("/study-plans/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "startDate": "2026-10-05",
                                  "examDate": "2026-10-06",
                                  "topics": [{"title": "Redes", "estimatedMinutes": 180}],
                                  "availability": [
                                    {"dayOfWeek": "MONDAY", "startTime": "18:00", "endTime": "19:00"}
                                  ],
                                  "reviewSessions": 0
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.canBeScheduled").value(false))
                .andExpect(jsonPath("$.missingMinutes").value(120));
    }

    @Test
    void returnsBadRequestForInvalidDates() throws Exception {
        mockMvc.perform(post("/study-plans/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {
                                  "startDate": "2026-10-07",
                                  "examDate": "2026-10-05",
                                  "topics": [{"title": "Redes", "estimatedMinutes": 60}],
                                  "availability": [
                                    {"dayOfWeek": "MONDAY", "startTime": "18:00", "endTime": "19:00"}
                                  ],
                                  "reviewSessions": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "La fecha de inicio debe ser anterior al examen."));
    }
}
