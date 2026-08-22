package fi.karivanttinen.fplassistant.user;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four outcomes of the one outbound call sign-up makes, against a stub that answers the way
 * Supabase's Admin API does.
 *
 * <p>The distinction that matters is the last two: "Supabase says no" and "Supabase could not be
 * asked" must never collapse into one answer — the same rule {@link FplEntryLookup} follows, and
 * for the same reason. Telling someone their password was rejected while the auth server is down
 * sends them rewriting a password that was fine.
 */
class SupabaseAdminClientTest {

    private static final String CREATED_ID = "3f1c9a44-2222-4222-8222-333333333333";

    private static HttpServer server;
    private static SupabaseAdminClient client;
    /** Port 1 refuses connections, which is what an unreachable Supabase looks like from here. */
    private static final SupabaseAdminClient unreachable =
            client("http://127.0.0.1:1", "service-role-key");

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The stub encodes the outcome in the address it is asked to create.
        server.createContext("/auth/v1/admin/users", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            int status;
            String response;
            if (body.contains("taken@example.com")) {
                status = 422;
                response = "{\"msg\":\"A user with this email address has already been registered\"}";
            } else if (body.contains("boom@example.com")) {
                status = 500;
                response = "{}";
            } else if (body.contains("shapeless@example.com")) {
                status = 200;
                response = "{\"something\":\"else\"}";
            } else {
                status = 200;
                response = "{\"id\":\"" + CREATED_ID + "\",\"email\":\"new@example.com\"}";
            }
            byte[] bytes = response.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        });
        server.start();
        client = client("http://127.0.0.1:" + server.getAddress().getPort(), "service-role-key");
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @Test
    void aCreatedUserComesBackWithItsId() {
        assertThat(client.createUser("new@example.com", "correct horse battery staple"))
                .isEqualTo(new SupabaseAdminClient.Created(UUID.fromString(CREATED_ID)));
    }

    /** Supabase's own reason travels back: only it knows why the address or password failed. */
    @Test
    void anAddressSupabaseRefusesIsRejectedWithItsReason() {
        assertThat(client.createUser("taken@example.com", "correct horse battery staple"))
                .isEqualTo(new SupabaseAdminClient.Rejected(
                        "A user with this email address has already been registered"));
    }

    @Test
    void aSupabaseThatFailsIsUnavailableRatherThanARejection() {
        assertThat(client.createUser("boom@example.com", "correct horse battery staple"))
                .isInstanceOf(SupabaseAdminClient.Unavailable.class);
    }

    /**
     * A 200 whose body carries no id is not a created account, whatever the status says: the id
     * is the only thing the caller can do anything with, and inventing one would stamp the
     * ledger with a user that does not exist.
     */
    @Test
    void aSuccessWithNoUsableIdIsUnavailable() {
        assertThat(client.createUser("shapeless@example.com", "correct horse battery staple"))
                .isInstanceOf(SupabaseAdminClient.Unavailable.class);
    }

    @Test
    void anUnreachableSupabaseIsUnavailable() {
        assertThat(unreachable.createUser("new@example.com", "correct horse battery staple"))
                .isInstanceOf(SupabaseAdminClient.Unavailable.class);
    }

    /**
     * A deploy with no service-role key cannot create accounts, and must not send an
     * unauthenticated request to find that out.
     */
    @Test
    void aBlankKeyIsNotConfiguredAndSendsNothing() {
        SupabaseAdminClient unconfigured =
                client("http://127.0.0.1:" + server.getAddress().getPort(), "");

        assertThat(unconfigured.configured()).isFalse();
        assertThat(unconfigured.createUser("new@example.com", "correct horse battery staple"))
                .isInstanceOf(SupabaseAdminClient.Unavailable.class);
    }

    private static SupabaseAdminClient client(String url, String key) {
        return new SupabaseAdminClient(RestClient.builder(), url, key);
    }
}
