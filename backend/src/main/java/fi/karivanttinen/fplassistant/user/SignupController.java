package fi.karivanttinen.fplassistant.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-serve sign-up, gated by an invite code with a hard account cap — both checked here,
 * because neither can be checked anywhere the client can reach.
 *
 * <p>Supabase's public sign-up stays off in the project. A code checked in the browser is
 * decoration: {@code supabase.auth.signUp} is a client call, and anyone can make it without our
 * form. So the backend creates the account itself, with the service-role key, and this endpoint
 * is the only door.
 *
 * <p>The one {@code permitAll} route under {@code /api/**} — there is no token at sign-up and
 * there cannot be one — and rate limited per IP like the other endpoints that cost something
 * outside this process. That limit blunts a script; the code and the cap are the actual gate.
 *
 * <p>An address that already exists answers differently from a fresh one, which is user
 * enumeration and is accepted here: the alternative — a fake success — is worse for a real
 * person mistyping their own address, and an attacker must already hold a valid invite code to
 * ask the question at all. Revisit if the code ever comes off.
 */
@RestController
@RequestMapping("/api/auth")
public class SignupController {

    private static final Logger log = LoggerFactory.getLogger(SignupController.class);

    public record SignupRequest(String email, String password, String inviteCode) {}

    public record SignupResponse(UUID userId) {}

    private final InviteLedger ledger;
    private final SupabaseAdminClient admin;
    private final String inviteCode;

    public SignupController(InviteLedger ledger, SupabaseAdminClient admin,
                            @Value("${fpl.invite.code:}") String inviteCode) {
        this.ledger = ledger;
        this.admin = admin;
        this.inviteCode = inviteCode;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signUp(@RequestBody(required = false) SignupRequest request) {
        String email = request == null ? null : request.email();
        String password = request == null ? null : request.password();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An email address and a password are required.");
        }
        if (!codeMatches(request.inviteCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That invite code is not valid.");
        }
        // Checked before a slot is reserved: a deploy with no service-role key cannot make the
        // call, and must not burn a slot discovering that.
        if (!admin.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sign-up is not available right now.");
        }

        long slot;
        try {
            slot = ledger.reserve(codeLabel());
        } catch (InviteLedger.CapReachedException e) {
            log.info("sign-up refused: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sign-up is full at the moment. Ask for a place and we will let you know when"
                            + " one frees up.");
        }

        return switch (admin.createUser(email.trim(), password)) {
            case SupabaseAdminClient.Created(UUID userId) -> {
                ledger.assign(slot, userId);
                yield ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(userId));
            }
            // The slot stays burnt, deliberately — see InviteLedger for why that is the failure
            // direction chosen. Supabase's own words travel back: only it knows whether the
            // address was taken or the password too short, and a refusal the user cannot read is
            // a refusal they cannot act on.
            case SupabaseAdminClient.Rejected(String message) ->
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
            case SupabaseAdminClient.Unavailable ignored ->
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Sign-up could not complete. Please try again shortly.");
        };
    }

    /**
     * Constant-time, and false for every code while {@code fpl.invite.code} is unset: a missing
     * secret closes sign-up rather than opening it.
     */
    private boolean codeMatches(String presented) {
        if (inviteCode.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(inviteCode.getBytes(StandardCharsets.UTF_8),
                (presented == null ? "" : presented).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The first eight hex characters of the code's SHA-256 — enough to tell one code from
     * another after a rotation, and not the code itself. A ledger row is not a secret store.
     */
    private String codeLabel() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(inviteCode.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Losing the label must not lose the account.
            return "unknown";
        }
    }
}
