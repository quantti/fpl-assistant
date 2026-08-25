package dev.quantti.fplassistant.config;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Bearer-token authentication for {@code /api/**}, verified against Supabase's published JWKS.
 *
 * <p>Nothing here holds a shared secret: Supabase signs with a private key and publishes the
 * public half, so this application only ever verifies. The {@code sub} claim of a verified token
 * is the user id, and it is the only place a user id may come from — never a request body, a
 * path variable or a header the client controls. Row-level security cannot be the mechanism,
 * because the application connects to Postgres as a role with {@code rolbypassrls = true}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        return http
                // The API is called with a bearer token and keeps no session cookie, so CSRF
                // has nothing to protect: an attacker's page cannot make the browser attach a
                // header it does not hold.
                .csrf(AbstractHttpConfigurer::disable)
                // Picks up the MVC CORS mappings in WebConfig; without this the security chain
                // would reject the preflight the browser sends from the Vite dev server.
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Fly's health check has no token to present.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The built frontend is served from static/ by this same application; it
                        // must load before the user has signed in.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico",
                                "/assets/**", "/*.svg", "/*.png", "/*.webmanifest").permitAll()
                        // Client-side routes: the browser navigates to them, so they arrive
                        // without a token — the script that holds one has not loaded yet. They
                        // forward to index.html and reveal nothing beyond it.
                        .requestMatchers(HttpMethod.GET, SpaForwardingController.CLIENT_ROUTES)
                        .permitAll()
                        // Chat included: this change ships the sign-in screen that sends the
                        // token. Requiring one while the deployed UI had no sign-in answered 401
                        // to every user in production on 2026-08-19 — the two only work merged
                        // together.
                        // The one permitAll route under /api/**: an account has to be created
                        // before there is a token to create it with. The gate is the invite code
                        // and the account cap, both checked in SignupController — never in the
                        // browser, which could not be trusted with either.
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // Fail closed: a new endpoint is protected until someone says otherwise.
                        // The previous catch-all permitAll made anything outside /api/** public
                        // by default — a new /admin controller, or an actuator endpoint added to
                        // the exposure list, would have been world-readable with no code change
                        // here and no failing test.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
                .build();
    }

    /**
     * How long a fetched key set is trusted before a refresh, and how often an unknown key id is
     * allowed to force one. Supabase rotates rarely; a token naming a key we do not have is far
     * more likely to be forged than newly rotated, so the refresh it triggers is rate limited.
     * Without that limit, anyone can turn one request into one outbound fetch — measured, not
     * assumed: the unconfigured decoder fetched the key set on every request with an unknown kid.
     */
    private static final Duration KEY_SET_TTL = Duration.ofMinutes(15);
    private static final Duration KEY_SET_MIN_REFRESH_INTERVAL = Duration.ofSeconds(30);
    private static final Duration KEY_SET_REFRESH_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    JwtDecoder jwtDecoder(@Value("${supabase.jwks-uri}") String jwkSetUri,
                          @Value("${supabase.issuer}") String issuer,
                          @Value("${supabase.audience}") String audience) {
        return decoder(jwkSetUri, issuer, audience);
    }

    /**
     * Built from the JWKS URI rather than the issuer URI: an issuer-based decoder fetches the
     * discovery document while the context starts, which would put a network call to Supabase in
     * front of every boot and every test. This one fetches lazily, on the first token it has to
     * verify, and caches the key set afterwards.
     *
     * <p>Exposed as a static factory so tests build the decoder the same way the application
     * does — including the cache and rate limit, which is where the interesting behaviour is.
     */
    public static JwtDecoder decoder(String jwkSetUri, String issuer, String audience) {
        issuer = collapseDoubledSlash(issuer);
        // The same repair, for the same reason: both properties are derived from SUPABASE_URL,
        // and the dashboard shows it with a trailing slash. A doubled slash in the key-set path
        // is not routed by Supabase's gateway, the fetch fails, and unverifiableIsUnauthorized
        // turns that into a 401 for every user — distinguishable from forged-token traffic only
        // by a WARN line.
        jwkSetUri = collapseDoubledSlash(jwkSetUri);
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.<SecurityContext>create(URI.create(jwkSetUri).toURL())
                    .retrying(true)
                    .rateLimited(KEY_SET_MIN_REFRESH_INTERVAL.toMillis())
                    .cache(KEY_SET_TTL.toMillis(), KEY_SET_REFRESH_TIMEOUT.toMillis())
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("supabase.jwks-uri is not a URL: " + jwkSetUri, e);
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys)
                // Supabase signs ES256 (verified against the live JWKS on 2026-08-19). The
                // default accepts RS256 only, and would reject every real token.
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                // RS256 as well: Supabase's key rotation lets a project move to an RSA key, and
                // accepting only what it publishes today makes a rotation a total authentication
                // outage — reported by the wrapper below as a suspected forgery, not an outage.
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(tokenValidator(issuer, audience));
        return unverifiableIsUnauthorized(decoder);
    }

    /**
     * "https://project.supabase.co/" is what the dashboard shows and what anyone would paste, and
     * it concatenates into a path with a double slash that nothing routes.
     *
     * <p>Public because sign-up builds a Supabase URL from the same variable and needs the same
     * repair; see {@code SupabaseAdminClient}.
     */
    public static String collapseDoubledSlash(String url) {
        return url.replaceAll("(?<!:)//+(?=auth)", "/");
    }

    /**
     * A token we cannot verify is a token we do not accept — {@code 401}, not {@code 500}.
     *
     * <p>Spring maps a {@link BadJwtException} to 401 and any other {@link JwtException} to a 500
     * {@code AuthenticationServiceException}. Both arise here from attacker-controlled input: a
     * token naming an unknown key id makes the decoder try to refresh the key set, and once the
     * rate limit above bites, that refresh fails. Answering 500 would let anyone paint the error
     * log and the dashboards by sending nonsense.
     *
     * <p>The cost is that a genuine Supabase outage also answers 401 rather than 500. The WARN
     * below is what distinguishes the two for whoever is on the other end of the alert.
     */
    private static JwtDecoder unverifiableIsUnauthorized(JwtDecoder delegate) {
        return token -> {
            try {
                return delegate.decode(token);
            } catch (BadJwtException e) {
                throw e;
            } catch (JwtException e) {
                log.warn("Could not verify a bearer token against the key set — treating it as"
                        + " unauthorized. Repeated occurrences with no forged-token traffic mean"
                        + " the key server is unreachable.", e);
                throw new BadJwtException("The token could not be verified", e);
            }
        };
    }

    /**
     * Signature aside, a token is only acceptable if it is unexpired, issued by our Supabase
     * project and meant for this audience. Exposed so tests exercise this exact chain rather
     * than a copy of it.
     */
    public static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(audience)));
    }
}
