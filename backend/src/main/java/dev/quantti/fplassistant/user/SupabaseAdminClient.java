package dev.quantti.fplassistant.user;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.ParameterizedTypeReference;
import dev.quantti.fplassistant.config.SecurityConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

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
    private final String resendUrl;
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
        this.usersUrl = SecurityConfig.collapseDoubledSlash(url + "/auth/v1/admin/users");
        this.resendUrl = SecurityConfig.collapseDoubledSlash(url + "/auth/v1/resend");
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
     * <p>Two calls, because no single GoTrue endpoint does both while public sign-up is off.
     * Established against the live project on 2026-08-26 rather than assumed:
     *
     * <ul>
     *   <li>{@code /auth/v1/signup} creates and sends in one call, but it is refused with
     *       {@code signup_disabled} for the anon key <b>and for the service role alike</b> —
     *       the service role is not exempt. Public sign-up has to stay off, since the anon key
     *       ships in the browser bundle, so this endpoint is unusable here.</li>
     *   <li>{@code /auth/v1/admin/users} with {@code email_confirm: false} creates the account
     *       unconfirmed and works with sign-up disabled, but sends nothing.</li>
     *   <li>{@code /auth/v1/admin/generate_link} returns a link and does not send it.</li>
     *   <li>{@code /auth/v1/resend} sends GoTrue's own confirmation mail, through the project's
     *       configured SMTP and template, for an account that already exists unconfirmed.</li>
     * </ul>
     *
     * <p>So: create unconfirmed, then ask GoTrue to send. The mail is Supabase's, which is the
     * point — no template to maintain here and no second provider in the request path.
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
                    // email_confirm false, explicitly. The address is confirmed by the person
                    // who owns it, by following the link, and not before.
                    .body(Map.of("email", email, "password", password, "email_confirm", false))
                    // Supabase says "no" with 400 or 422 depending on which check failed, and
                    // both are answers rather than failures. Everything else falls through to
                    // the status check below rather than being thrown, so a 500 and a refusal
                    // cannot arrive by the same path.
                    .retrieve()
                    .onStatus(status -> status.value() == 400 || status.value() == 422,
                            (request, res) -> { })
                    .toEntity(new ParameterizedTypeReference<Map<String, Object>>() {});
            return interpret(response, email);
        } catch (RestClientException e) {
            // Only the failure type. The message embeds the request URI, and the request carried
            // a password.
            log.warn("Could not reach Supabase to create an account ({})",
                    e.getClass().getSimpleName());
            return new Unavailable();
        }
    }

    private Result interpret(ResponseEntity<Map<String, Object>> response, String email) {
        Map<String, Object> body = response.getBody() == null ? Map.of() : response.getBody();
        if (response.getStatusCode().is2xxSuccessful()) {
            UUID userId = userId(body.get("id"));
            if (userId != null) {
                sendConfirmation(email);
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

    /**
     * Asks GoTrue to send its own confirmation mail for an account that already exists
     * unconfirmed.
     *
     * <p>Failure is logged and swallowed on purpose. The account is created and the place is
     * taken by this point, so throwing would turn a mail that did not go out into a sign-up that
     * appears to have failed — and the person would try again, be told the address is taken, and
     * have no way forward. An unsent mail is recoverable: the same endpoint sends it again.
     *
     * <p>A {@code 429} here is the built-in Supabase mailer's rate limit, not ours. It is a few
     * messages an hour and it is why the project needs custom SMTP configured before real
     * sign-ups; the log line says so rather than leaving it to be guessed at.
     */
    private void sendConfirmation(String email) {
        try {
            restClient.post()
                    .uri(resendUrl)
                    .header("apikey", serviceRoleKey)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "signup", "email", email))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                log.error("the confirmation email was rate limited by Supabase's built-in mailer."
                        + " Configure custom SMTP in the project; until then only a few messages"
                        + " an hour are delivered and this account has not received one.");
            } else {
                log.error("the confirmation email could not be sent ({}). The account exists and"
                        + " unverified; sending it again is the fix.", e.getStatusCode().value());
            }
        } catch (RestClientException e) {
            log.error("the confirmation email could not be sent. The account exists and is"
                    + " unverified; sending it again is the fix.", e);
        }
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
