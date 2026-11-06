package com.openimage.security;

import com.openimage.ai.worker.AiWorkerAuthenticationFilter;
import com.openimage.ai.worker.AiWorkerTokenService;
import com.openimage.domain.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = SecurityConfigStartupTest.ProbeController.class,
    properties = {
        "openimage.security.jwt-secret=fixture-only-secret-with-at-least-thirty-two-bytes",
        "openimage.security.access-token-minutes=15"
    }
)
@Import({
    SecurityConfig.class,
    AiWorkerAuthenticationFilter.class,
    JwtAuthenticationFilter.class,
    UserRequestIpTrackingFilter.class,
    JsonAuthenticationEntryPoint.class,
    JsonAccessDeniedHandler.class
})
class SecurityConfigStartupTest {
    @Autowired
    private MockMvc mvc;

    @MockBean
    private AiWorkerTokenService workerTokens;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserMapper users;

    @Test
    void securityChainStartsAndWorkerApiRejectsPlainHttpBeforeAuthentication() throws Exception {
        mvc.perform(post("/api/internal/picpilot-worker/v1/heartbeat"))
            .andExpect(status().isUpgradeRequired())
            .andExpect(jsonPath("$.error.code").value("HTTPS_REQUIRED"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String probe() {
            return "ok";
        }
    }
}
