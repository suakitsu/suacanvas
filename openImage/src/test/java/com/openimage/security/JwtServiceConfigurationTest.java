package com.openimage.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceConfigurationTest {
    @Test
    void rejectsMissingShortAndPublishedPlaceholderSecretsWithoutEchoingThem() {
        for (String secret : new String[] {null, "", "short", "change-me-change-me-change-me-change-me-32bytes"}) {
            assertThatThrownBy(() -> new JwtService(new SecurityProperties(secret, 15)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("JWT_SECRET must be a private random value of at least 32 bytes");
        }
    }

    @Test
    void independentlyConfiguredInstancesCannotAcceptEachOthersTokens() {
        JwtService first = new JwtService(new SecurityProperties("fixture-first-private-secret-at-least-32-bytes", 15));
        JwtService second = new JwtService(new SecurityProperties("fixture-second-private-secret-at-least-32-bytes", 15));
        CurrentUser user = new CurrentUser("fixture-id", "fixture@example.com", "USER");
        String token = first.issue(user);
        assertThat(first.parse(token)).isEqualTo(user);
        assertThatThrownBy(() -> second.parse(token)).isInstanceOf(io.jsonwebtoken.security.SignatureException.class);
    }
}
