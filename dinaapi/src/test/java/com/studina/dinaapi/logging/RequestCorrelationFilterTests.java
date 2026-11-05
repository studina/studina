package com.studina.dinaapi.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTests {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearRequestId() {
        MDC.remove(RequestCorrelationFilter.MDC_KEY);
    }

    @Test
    void generatesAServerIdAndExposesItWithoutTrustingTheClientHeader() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER, "private-untrusted-client-value");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            String id = MDC.get(RequestCorrelationFilter.MDC_KEY);
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo(id);
            assertThat(id).isNotEqualTo("private-untrusted-client-value");
        });

        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    void restoresThePreviousContextEvenWhenTheRequestFails() {
        MDC.put(RequestCorrelationFilter.MDC_KEY, "outer-context");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isEqualTo(response.getHeader(RequestCorrelationFilter.HEADER));
            throw new ServletException("private request error");
        })).isInstanceOf(ServletException.class);
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isEqualTo("outer-context");
    }

    @Test
    void doesNotReuseAnIdOnTheNextRequestOnTheSameThread() throws Exception {
        var first = new MockHttpServletResponse();
        var second = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> {});
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> {});
        assertThat(first.getHeader(RequestCorrelationFilter.HEADER))
                .isNotEqualTo(second.getHeader(RequestCorrelationFilter.HEADER));
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }
}
