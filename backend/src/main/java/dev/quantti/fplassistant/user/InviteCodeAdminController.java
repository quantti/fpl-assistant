package dev.quantti.fplassistant.user;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operating the invite codes: issue one, switch it off, move its ceiling, see who came in on it,
 * release the places a failed sign-up burnt.
 *
 * <p>Who may call this is decided on the path in {@code SecurityConfig} through
 * {@code AdminAccess}, not here — there is no check in this class to forget, and a second
 * controller under {@code /api/admin} inherits the same gate. No method takes a user id.
 *
 * <p>The state of a code is computed on the server from the same rule sign-up refuses by
 * ({@link InviteCode#state}), so the page can never show a code as open that sign-up would turn
 * away. Every mutation answers with the row as it now is, for the same reason.
 *
 * <p>There is no {@code DELETE} on a code. Redemption rows point at it, and deleting one would
 * erase which code those accounts came in on; disabling is how a code is closed.
 */
@RestController
@RequestMapping("/api/admin/invite-codes")
public class InviteCodeAdminController {

    /**
     * How old a burnt place must be before {@code release-burnt} will delete it. A younger row
     * may be a sign-up whose Supabase call has not answered yet.
     */
    static final Duration BURNT_GRACE = Duration.ofMinutes(10);

    private final InviteLedger ledger;

    public InviteCodeAdminController(InviteLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * One code as the page shows it. Absent rather than null for the optional numbers, so
     * "no ceiling" is the missing field and not a zero.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CodeView(long id, String code, String label, String note, Integer maxUses,
                           int uses, Integer remaining, int accounts, int burnt,
                           OffsetDateTime lastRedeemedAt, String benefitKind, Integer benefitValue,
                           boolean active, OffsetDateTime validFrom, OffsetDateTime validUntil,
                           InviteCode.State state, OffsetDateTime createdAt) {

        static CodeView of(InviteLedger.CodeSummary summary, OffsetDateTime now) {
            InviteCode c = summary.code();
            return new CodeView(c.id(), c.code(), c.label(), c.note(), c.maxUses(), c.uses(),
                    c.remaining(), summary.accounts(), summary.burnt(), summary.lastRedeemedAt(),
                    c.benefitKind(), c.benefitValue(), c.active(), c.validFrom(), c.validUntil(),
                    c.state(now), c.createdAt());
        }
    }

    public record ListResponse(int accountCap, int placesTaken, int burnt, List<CodeView> codes) {}

    public record CreateRequest(String code, String label, Integer maxUses, String benefitKind,
                                Integer benefitValue, OffsetDateTime validFrom,
                                OffsetDateTime validUntil, String note) {}

    public record MaxUsesRequest(Integer maxUses) {}

    public record RedemptionView(long id, UUID userId, OffsetDateTime redeemedAt) {}

    public record ReleaseResponse(int released, List<String> reconciled) {}

    @GetMapping
    public ListResponse list() {
        OffsetDateTime now = OffsetDateTime.now();
        InviteLedger.Summary summary = ledger.summary();
        return new ListResponse(summary.accountCap(), summary.placesTaken(), summary.burnt(),
                summary.codes().stream().map(s -> CodeView.of(s, now)).toList());
    }

    @PostMapping
    public ResponseEntity<CodeView> create(@RequestBody(required = false) CreateRequest request) {
        if (request == null || request.code() == null || request.code().isBlank()) {
            throw bad("A code is required.");
        }
        if (request.maxUses() != null && request.maxUses() < 1) {
            throw bad("maxUses must be at least 1, or absent for no ceiling.");
        }
        String benefitKind = request.benefitKind() == null ? InviteCode.ACCESS : request.benefitKind();
        if (!InviteCode.ACCESS.equals(benefitKind) && !InviteCode.DISCOUNT_PERCENT.equals(benefitKind)) {
            throw bad("benefitKind must be 'access' or 'discount_percent'.");
        }
        Integer benefitValue = InviteCode.ACCESS.equals(benefitKind) ? null : request.benefitValue();
        if (InviteCode.DISCOUNT_PERCENT.equals(benefitKind)
                && (benefitValue == null || benefitValue < 1 || benefitValue > 100)) {
            throw bad("A discount code needs a benefitValue of 1-100.");
        }
        if (request.validFrom() != null && request.validUntil() != null
                && request.validUntil().isBefore(request.validFrom())) {
            throw bad("validUntil must not be before validFrom.");
        }
        String code = request.code().trim();
        String label = request.label() == null || request.label().isBlank() ? code : request.label().trim();
        InviteCode created = ledger.create(code, label, request.maxUses(), benefitKind, benefitValue,
                        request.validFrom(), request.validUntil(), request.note())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "A code with that name already exists."));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(created.id()));
    }

    @PostMapping("/{id}/disable")
    public CodeView disable(@PathVariable long id) {
        return setActive(id, false);
    }

    @PostMapping("/{id}/enable")
    public CodeView enable(@PathVariable long id) {
        return setActive(id, true);
    }

    private CodeView setActive(long id, boolean active) {
        if (!ledger.setActive(id, active)) {
            throw notFound(id);
        }
        return view(id);
    }

    @PutMapping("/{id}/max-uses")
    public CodeView setMaxUses(@PathVariable long id,
                               @RequestBody(required = false) MaxUsesRequest request) {
        if (request == null || request.maxUses() == null) {
            throw bad("maxUses is required. To remove the ceiling, DELETE this resource.");
        }
        if (request.maxUses() < 1) {
            throw bad("maxUses must be at least 1.");
        }
        return applyMaxUses(id, request.maxUses());
    }

    @DeleteMapping("/{id}/max-uses")
    public CodeView clearMaxUses(@PathVariable long id) {
        return applyMaxUses(id, null);
    }

    private CodeView applyMaxUses(long id, Integer maxUses) {
        try {
            ledger.setMaxUses(id, maxUses).orElseThrow(() -> notFound(id));
        } catch (InviteLedger.CeilingBelowUsesException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return view(id);
    }

    @GetMapping("/{id}/redemptions")
    public List<RedemptionView> redemptions(@PathVariable long id) {
        if (ledger.findById(id).isEmpty()) {
            throw notFound(id);
        }
        return ledger.redemptionsOf(id).stream()
                .map(r -> new RedemptionView(r.id(), r.userId(), r.redeemedAt()))
                .toList();
    }

    @PostMapping("/release-burnt")
    public ReleaseResponse releaseBurnt() {
        InviteLedger.Release release = ledger.releaseBurnt(BURNT_GRACE);
        return new ReleaseResponse(release.released(), release.reconciled());
    }

    private CodeView view(long id) {
        return ledger.summaryOf(id).map(s -> CodeView.of(s, OffsetDateTime.now()))
                .orElseThrow(() -> notFound(id));
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No invite code with id " + id + ".");
    }
}
