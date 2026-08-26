package dev.quantti.fplassistant.user;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * @param verificationRequired always true now, and stated rather than implied: the frontend
     *        has to know not to try signing in, and a client reading a bare 201 as "you are in"
     *        is exactly the bug this replaced.
     */
    public record SignupResponse(UUID userId, boolean verificationRequired) {}

    private final InviteLedger ledger;
    private final SupabaseAdminClient admin;

    public SignupController(InviteLedger ledger, SupabaseAdminClient admin) {
        this.ledger = ledger;
        this.admin = admin;
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signUp(@RequestBody(required = false) SignupRequest request) {
        String email = request == null ? null : request.email();
        String password = request == null ? null : request.password();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An email address and a password are required.");
        }
        // Read before anything is reserved, so an unknown or closed code is answered as such
        // even on a deploy that could not create the account anyway. The authoritative check is
        // inside reserve(), under the lock; this one only decides which refusal to send.
        OffsetDateTime now = OffsetDateTime.now();
        InviteCode peeked = ledger.findByCode(request.inviteCode()).orElse(null);
        if (peeked == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That invite code is not valid.");
        }
        String refusal = peeked.refusalReason(now);
        if (refusal != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
        }
        // Checked before a place is reserved: a deploy with no service-role key cannot make the
        // call, and must not burn a place discovering that.
        if (!admin.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Sign-up is not available right now.");
        }

        InviteLedger.Reservation reservation;
        try {
            reservation = ledger.reserve(request.inviteCode());
        } catch (InviteLedger.CodeRefusedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (InviteLedger.CapReachedException e) {
            log.info("sign-up refused: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Sign-up is full at the moment. Ask for a place and we will let you know when"
                            + " one frees up.");
        }
        long slot = reservation.slotId();

        return switch (admin.createUser(email.trim(), password)) {
            case SupabaseAdminClient.Created(UUID userId) -> {
                ledger.assign(slot, userId);
                log.info("sign-up: account created on code {}, awaiting email confirmation",
                        reservation.code().label());
                yield ResponseEntity.status(HttpStatus.CREATED)
                        .body(new SignupResponse(userId, true));
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
}
