package fi.karivanttinen.fplassistant.user;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import fi.karivanttinen.fplassistant.config.SecurityConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Creates Supabase accounts with the service-role key.
 *
 * <p>Sign-up is a backend call because a browser one cannot be gated: {@code supabase.auth.signUp}
 * is a client call anyone can make without our form, so a code checked in the browser is
 * decoration. Public sign-up therefore stays off in the project, and this is the only way in.
 *
 * <p>The key bypasses row-level security entirely and belongs in the same handling class as the
 * database password. Nothing here logs the request: the body carries a password and the address
 * of a real person.
 *
 * <p>Bounded like {@link FplEntryLookup}, and for the same reason — the two failures must stay
 * distinguishable. "Supabase rejected this password" and "Supabase could not be reached" are
 * different sentences to a user, and collapsing them sends someone rewriting a password that was
 * never the problem.
 */
@Component
public class SupabaseAdminClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAdminClient.class);

    /** Someone is waiting on this, but account creation is a write — looser than the FPL check. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** What the caller has to answer for: created, refused, or nothing we can say. */
    public sealed interface Result {}

    public record Created(UUID userId) implements Result {}

    /** Supabase's own words — the address is taken, the password is too short. */
    public record Rejected(String message) implements Result {}

    /** Nothing was learned about the request. It may or may not have created an account. */
    public record Unavailable() implements Result {}

    private final RestClient restClient;
    private final String usersUrl;
    private final String serviceRoleKey;

    public SupabaseAdminClient(RestClient.Builder builder,
                               @Value("${supabase.url}") String url,
                               @Value("${supabase.service-role-key:}") String serviceRoleKey) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.requestFactory(factory).build();
        // The same repair SecurityConfig makes to the issuer, and the same call: SUPABASE_URL is
        // what the dashboard shows, and the dashboard shows it with a trailing slash. A doubled
        // slash is not routed by Supabase's gateway, so the call would fail for a reason nothing
        // here could report usefully.
        this.usersUrl = SecurityConfig.collapseDoubledSlash(url + "/auth/v1/signup");
        this.serviceRoleKey = serviceRoleKey;
    }

    /** Whether this deploy can create accounts at all. A blank key closes sign-up. */
    public boolean configured() {
        return !serviceRoleKey.isBlank();
    }

    /**
     * Creates an account with its address <b>unconfirmed</b>, and asks Supabase to send the
     * confirmation email.
     *
     * <p>This calls {@code /auth/v1/signup} rather than {@code /auth/v1/admin/users}, and the
     * difference is the whole point: the admin endpoint creates a user and sends nothing, which
     * is why the project's own {@code email_confirm: true} was the only way it could ever be
     * signed into. {@code /signup} is GoTrue's own registration path — it creates the user
     * unconfirmed and sends the confirmation mail through the project's configured SMTP, using
     * the project's own template.
     *
     * <p>It is called <b>with the service-role key</b>, which is what keeps the invite code
     * meaningful: the endpoint is public, so the browser could reach it directly with the anon
     * key, and public sign-up must therefore be turned off in the Supabase project. The service
     * role is expected to be exempt from that switch. <b>Verify that before relying on it</b> —
     * if it is not exempt, the fallback is {@code admin/users} with {@code email_confirm: false}
     * plus {@code admin/generate_link}, and sending the mail ourselves.
     */
    public Result createUser(String email, String password) {
        if (!configured()) {
            log.warn("Sign-up was attempted with no service-role key configured");
            return new Unavailable();
        }
        try {
            ResponseEntity<Map<String, Object>> response = restClient.post()
                    .uri(usersUrl)
                    .header("apikey", serviceRoleKey)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    // No email_confirm. The address is confirmed by the person who owns it,
                    // by following the link Supabase sends, and not before.
                    .body(Map.of("email", email, "password", password))
                    // Supabase says "no" with 400 or 422 depending on which check failed, and
                    // both are answers rather than failures. Everything else falls through to
                    // the status check below rather than being thrown, so a 500 and a refusal
                    // cannot arrive by the same path.
                    .retrieve()
                    .onStatus(status -> status.value() == 400 || status.value() == 422,
                            (request, res) -> { })
                    .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});
            return interpret(response);
        } catch (RestClientException e) {
            // Only the failure type. The message embeds the request URI, and the request carried
            // a password.
            log.warn("Could not reach Supabase to create an account ({})",
                    e.getClass().getSimpleName());
            return new Unavailable();
        }
    }

    private Result interpret(ResponseEntity<Map<String, Object>> response) {
        Map<String, Object> body = response.getBody() == null ? Map.of() : response.getBody();
        if (response.getStatusCode().is2xxSuccessful()) {
            UUID userId = userId(body.get("id"));
            if (userId != null) {
                return new Created(userId);
            }
            // A success whose body carries no usable id is not a created account, whatever the
            // status says: stamping the ledger needs the id, and inventing one would name a user
            // that does not exist.
            log.warn("Supabase answered {} to an account creation with no usable id in the body",
                    response.getStatusCode().value());
            return new Unavailable();
        }
        return new Rejected(reason(body));
    }

    private static UUID userId(Object id) {
        try {
            return id == null ? null : UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Supabase names the field differently depending on which layer refused — GoTrue answers
     * {@code msg}, its newer errors {@code error_description} or {@code message}. A refusal the
     * user cannot read is a refusal they cannot act on, so the fallback still says something.
     */
    private static String reason(Map<String, Object> body) {
        for (String field : new String[] {"msg", "error_description", "message", "error"}) {
            Object value = body.get(field);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "That email address or password was not accepted.";
    }
}
