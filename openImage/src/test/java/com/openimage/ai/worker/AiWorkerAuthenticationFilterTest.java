package com.openimage.ai.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWorkerAuthenticationFilterTest {
    @Test
    void rejectsPlainHttpBeforeReadingAuthorization() throws Exception {
        AiWorkerTokenService tokens = mock(AiWorkerTokenService.class);
        AiWorkerAuthenticationFilter filter = new AiWorkerAuthenticationFilter(tokens, new ObjectMapper());
        MockHttpServletRequest request = request();
        request.addHeader("Authorization", "Bearer ppw_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(426);
        assertThat(response.getContentAsString()).contains("HTTPS_REQUIRED");
        verify(tokens, never()).authenticate("ppw_secret");
    }

    @Test
    void acceptsWorkerTokenOverForwardedHttpsAndSetsIndependentPrincipal() throws Exception {
        AiWorkerTokenService tokens = mock(AiWorkerTokenService.class);
        AiWorkerPrincipal principal = new AiWorkerPrincipal("awt-1", "worker", "ppw_prefix");
        when(tokens.authenticate("ppw_secret")).thenReturn(principal);
        AiWorkerAuthenticationFilter filter = new AiWorkerAuthenticationFilter(tokens, new ObjectMapper());
        MockHttpServletRequest request = request();
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("Authorization", "Bearer ppw_secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(AiWorkerAuthenticationFilter.PRINCIPAL_ATTRIBUTE))
            .isEqualTo(principal);
        verify(tokens).authenticate("ppw_secret");
        verify(chain).doFilter(request, response);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            AiWorkerAuthenticationFilter.PREFIX + "/claim"
        );
        request.setRequestURI(AiWorkerAuthenticationFilter.PREFIX + "/claim");
        return request;
    }
}
