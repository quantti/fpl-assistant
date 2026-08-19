package fi.karivanttinen.fplassistant.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * A local stand-in for Supabase's signing key and JWKS endpoint.
 *
 * <p>The authentication tests need to mint tokens that are genuinely valid, genuinely expired,
 * or genuinely signed by the wrong key — which is impossible against the real Supabase, where
 * we hold no private key and cannot forge a bad one. So the tests verify against this key
 * instead, using the same decoder configuration and the same validator chain the application
 * builds in {@code SecurityConfig}.
 *
 * <p>ES256, because that is what Supabase's live JWKS publishes (checked 2026-08-19). Verifying
 * against an RSA stand-in would let a decoder that only accepts RS256 pass its tests and then
 * reject every real token.
 */
public final class TestJwks {

    public static final String ISSUER = "https://test-project.supabase.co/auth/v1";
    public static final String AUDIENCE = "authenticated";
    public static final String USER_ID = "8f14e45f-ceea-467a-9c8c-1ffc7a3e2b91";

    private static final ECKey KEY = generate("test-key");
    /** A second key, never published: tokens signed with it must fail signature verification. */
    private static final ECKey FOREIGN_KEY = generate("foreign-key");

    private static final AtomicInteger JWKS_REQUESTS = new AtomicInteger();
    private static final String JWKS_URI = startServer();

    private TestJwks() {
    }

    public static String jwksUri() {
        return JWKS_URI;
    }

    /** How many times the JWKS endpoint has been fetched — the decoder should cache after one. */
    public static int jwksRequestCount() {
        return JWKS_REQUESTS.get();
    }

    public static String validToken() {
        return token(KEY, ISSUER, AUDIENCE, Instant.now().plusSeconds(600));
    }

    public static String expiredToken() {
        return token(KEY, ISSUER, AUDIENCE, Instant.now().minusSeconds(60));
    }

    public static String wrongIssuerToken() {
        return token(KEY, "https://someone-else.supabase.co/auth/v1", AUDIENCE,
                Instant.now().plusSeconds(600));
    }

    public static String wrongAudienceToken() {
        return token(KEY, ISSUER, "some-other-service", Instant.now().plusSeconds(600));
    }

    public static String foreignKeyToken() {
        return token(FOREIGN_KEY, ISSUER, AUDIENCE, Instant.now().plusSeconds(600));
    }

    private static String token(ECKey key, String issuer, String audience, Instant expiry) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(USER_ID)
                .issuer(issuer)
                .audience(List.of(audience))
                .issueTime(Date.from(Instant.now().minusSeconds(5)))
                .expirationTime(Date.from(expiry))
                .claim("role", "authenticated")
                .claim("email", "test@example.com")
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("could not sign the test token", e);
        }
        return jwt.serialize();
    }

    private static ECKey generate(String keyId) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not generate a test signing key", e);
        }
    }

    private static String startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                JWKS_REQUESTS.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        } catch (IOException e) {
            throw new IllegalStateException("could not start the test JWKS server", e);
        }
    }
}
