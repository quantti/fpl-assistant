package fi.karivanttinen.fplassistant.testsupport;

import fi.karivanttinen.fplassistant.config.SecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Points the application's own decoder at {@link TestJwks} instead of Supabase, so tests can mint
 * tokens that are genuinely valid, expired or wrongly signed.
 *
 * <p>Shared rather than repeated per test class: the decoder is built by
 * {@link SecurityConfig#decoder}, and a copy of this in every authenticated-endpoint test would
 * mean changing the way tests build it in as many places as there are tests.
 */
@TestConfiguration
public class LocalSigningKey {

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder() {
        return SecurityConfig.decoder(TestJwks.jwksUri(), TestJwks.ISSUER, TestJwks.AUDIENCE);
    }
}
