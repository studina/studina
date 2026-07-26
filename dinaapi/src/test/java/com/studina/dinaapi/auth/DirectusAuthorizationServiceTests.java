package com.studina.dinaapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DirectusAuthorizationServiceTests {

    private final DirectusAuthorizationService service =
            new DirectusAuthorizationService();

    @Test
    void rejectsMissingAuthorizationHeader() {
        assertThatThrownBy(() -> service.requireUserId(null))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED)
                );
    }

    @Test
    void rejectsNonBearerAuthorizationHeader() {
        assertThatThrownBy(() -> service.requireUserId("Basic abc"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.UNAUTHORIZED)
                );
    }

    @Test
    void rejectsBlankFileIdBeforeCallingDirectus() {
        assertThatThrownBy(() -> service.requireFileOwnership("user-id", " "))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST)
                );
    }
}
