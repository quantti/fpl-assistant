package dev.quantti.fplassistant.config;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's user id, from the verified token's {@code sub} claim and nowhere else.
 *
 * <p>The token is verified by the time this runs, but "verified" does not mean the {@code sub}
 * claim is a UUID — a token without one, or with a different subject format, would otherwise
 * become a 500 with a stack trace on a path anyone can reach. {@link SecurityConfig} goes to some
 * lengths to keep attacker-reachable token problems at 401; this keeps that true one layer up.
 *
 * <p>Shared rather than copied per controller: three endpoints started from the same claim, and
 * the copy that forgets the guard is the one that answers 500.
 */
public final class TokenSubject {

    private TokenSubject() {}

    public static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "The token carries no usable subject claim.");
        }
    }
}
