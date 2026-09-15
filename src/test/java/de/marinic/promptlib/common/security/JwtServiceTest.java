package de.marinic.promptlib.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final long ONE_HOUR = 3_600_000;

    // Regression test: there used to be a public default secret in application.properties, so a
    // forgotten JWT_SECRET in a deployment went unnoticed and anyone could forge tokens.
    @Test
    void refusesToStartWithoutASecret() {
        assertThatThrownBy(() -> new JwtService(new JwtProperties("", ONE_HOUR)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtService(new JwtProperties("   ", ONE_HOUR)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokenSignedWithOneSecretIsRejectedByAnother() {
        JwtService a = new JwtService(new JwtProperties("secret-of-instance-a-at-least-32-bytes-long!", ONE_HOUR));
        JwtService b = new JwtService(new JwtProperties("secret-of-instance-b-at-least-32-bytes-long!", ONE_HOUR));
        UUID userId = UUID.randomUUID();

        String token = a.generateToken(userId, "noah@example.com");

        assertThat(a.isValid(token)).isTrue();
        assertThat(a.extractUserId(token)).isEqualTo(userId);
        assertThat(b.isValid(token)).isFalse();
    }
}
