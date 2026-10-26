package com.studina.dinaapi.studyplan;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StudyPlanControllerIntegrationTests {

    private MockMvc mockMvc;
    private GeminiStudyPlanGenerator generator;

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
    void generatesAPlanThroughTheHttpContract()
            throws Exception {

        GeneratedStudyPlan result
                = new GeneratedStudyPlan(
                        true,
                        60,
                        240,
                        0,
                        List.of(
                                new StudySession(
                                        "Estudio: Redes",
                                        LocalDateTime.parse(
                                                "2026-10-05T18:00:00"
                                        ),
                                        LocalDateTime.parse(
                                                "2026-10-05T19:00:00"
                                        ),
                                        StudySessionType.STUDY,
                                        0,
                                        1
                                ),
                                new StudySession(
                                        "Simulacro",
                                        LocalDateTime.parse(
                                                "2026-10-06T18:00:00"
                                        ),
                                        LocalDateTime.parse(
                                                "2026-10-06T19:00:00"
                                        ),
                                        StudySessionType.MOCK_EXAM,
                                        null,
                                        null
                                )
                        ),
                        "Plan generado."
                );

        when(
                generator.generate(
                        any(
                                GenerateAiStudyPlanRequest.class
                        )
                )
        ).thenReturn(
                result
        );

        mockMvc.perform(
                post(
                        "/study-plans/generate"
                )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(
                                """
                                        {
                                          "milestoneType": "MIDTERM",
                                          "milestoneTitle": "Parcial",
                                          "milestoneDescription": "",
                                          "startDate": "2026-10-05",
                                          "examDate": "2026-10-07",
                                          "topics": [
                                            {
                                              "topicId": "1",
                                              "title": "Redes",
                                              "description": "",
                                              "masteryScore": 0.4,
                                              "level": "weak",
                                              "coverage": 1.0,
                                              "totalResources": 1,
                                              "totalAttempts": 1
                                            }
                                          ],
                                          "availability": [
                                            {
                                              "dayOfWeek": "MONDAY",
                                              "startTime": "18:00",
                                              "endTime": "20:00"
                                            },
                                            {
                                              "dayOfWeek": "TUESDAY",
                                              "startTime": "18:00",
                                              "endTime": "20:00"
                                            }
                                          ],
                                          "reviewSessions": 1
                                        }
                                        """
                        )
        )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.canBeScheduled"
                        ).value(
                                true
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.sessions.length()"
                        ).value(
                                2
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.sessions[0].type"
                        ).value(
                                "STUDY"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.sessions[1].type"
                        ).value(
                                "MOCK_EXAM"
                        )
                );
    }

    @Test
    void returnsUnprocessableEntityWhenTheValidRequestDoesNotFit()
            throws Exception {

        GeneratedStudyPlan result
                = new GeneratedStudyPlan(
                        false,
                        180,
                        60,
                        120,
                        List.of(),
                        "La disponibilidad no alcanza."
                );

        when(
                generator.generate(
                        any(
                                GenerateAiStudyPlanRequest.class
                        )
                )
        ).thenReturn(
                result
        );

        mockMvc.perform(
                post(
                        "/study-plans/generate"
                )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(
                                """
                                        {
                                          "milestoneType": "MIDTERM",
                                          "milestoneTitle": "Parcial",
                                          "milestoneDescription": "",
                                          "startDate": "2026-10-05",
                                          "examDate": "2026-10-06",
                                          "topics": [
                                            {
                                              "topicId": "1",
                                              "title": "Redes",
                                              "description": "",
                                              "masteryScore": 0.4,
                                              "level": "weak",
                                              "coverage": 1.0,
                                              "totalResources": 1,
                                              "totalAttempts": 1
                                            }
                                          ],
                                          "availability": [
                                            {
                                              "dayOfWeek": "MONDAY",
                                              "startTime": "18:00",
                                              "endTime": "19:00"
                                            }
                                          ],
                                          "reviewSessions": 0
                                        }
                                        """
                        )
        )
                .andExpect(
                        status().isUnprocessableEntity()
                )
                .andExpect(
                        jsonPath(
                                "$.canBeScheduled"
                        ).value(
                                false
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.missingMinutes"
                        ).value(
                                120
                        )
                );
    }

    @Test
    void returnsBadRequestForInvalidDates()
            throws Exception {

        when(
                generator.generate(
                        any(
                                GenerateAiStudyPlanRequest.class
                        )
                )
        ).thenThrow(
                new IllegalArgumentException(
                        "La fecha de inicio debe ser anterior al examen."
                )
        );

        mockMvc.perform(
                post(
                        "/study-plans/generate"
                )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(
                                """
                                        {
                                          "milestoneType": "MIDTERM",
                                          "milestoneTitle": "Parcial",
                                          "milestoneDescription": "",
                                          "startDate": "2026-10-07",
                                          "examDate": "2026-10-05",
                                          "topics": [
                                            {
                                              "topicId": "1",
                                              "title": "Redes",
                                              "description": "",
                                              "masteryScore": 0.4,
                                              "level": "weak",
                                              "coverage": 1.0,
                                              "totalResources": 1,
                                              "totalAttempts": 1
                                            }
                                          ],
                                          "availability": [
                                            {
                                              "dayOfWeek": "MONDAY",
                                              "startTime": "18:00",
                                              "endTime": "19:00"
                                            }
                                          ],
                                          "reviewSessions": 0
                                        }
                                        """
                        )
        )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        jsonPath(
                                "$.error"
                        ).value(
                                "La fecha de inicio debe ser anterior al examen."
                        )
                );
    }
}
