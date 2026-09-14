# KAR-54 — Invite-code admin API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Progress is the single `**PROGRESS:**` line below, updated once per task — no per-step checkboxes.

**PROGRESS:** Task 4/4 done.

Issue: [KAR-54](https://linear.app/quantti/issue/KAR-54/invite-code-admin-api-per-code-ceilings-disable-attribution).
Baseline: `origin/master` at `4a925d9`. Branch `invite-code-limits`, own worktree
`.claude/worktrees/invite-code-limits`. The page that uses this API is KAR-55, not this plan.

**Goal:** An authenticated, admin-only REST API under `/api/admin/invite-codes` that lists every invite code with its attribution counts, creates codes, disables and enables them, sets or removes a code's ceiling, lists a code's redemptions, and releases burnt places — so none of that is hand-written SQL in the Supabase editor any more.

**Architecture:** The data model already exists (`fpl_invite_code`, `fpl_invite_redemption.code_id`, PR #52); this adds reads and writes on `InviteLedger`, a `state()` on `InviteCode` that `refusalReason()` is refactored onto so the API and the sign-up gate cannot disagree, one controller, and an `AuthorizationManager` on the `/api/admin/**` path in `SecurityConfig` driven by a configured set of JWT `sub` ids. `GET /api/profile` gains `admin: boolean` for the frontend.

**Tech Stack:** Spring Boot 4.1, Spring Security 7.1 (`AuthorizationManager.authorize`, not the removed `check`), `JdbcTemplate`, JUnit 5 + MockMvc + `TestJwks`/`LocalSigningKey`, Postgres test container from `scripts/test-db.sh`.

**Spec:** the Linear issue KAR-54 (its description is the spec; there is no separate file).

## Global Constraints

- Every table name is a property (`fpl.user.invite-code-table-name`, `fpl.user.invite-redemption-table-name`); never hard-code `fpl_invite_code`.
- No admin endpoint takes a user id in a body or path. The caller's identity is the verified token's `sub` and nothing else.
- Codes are never deleted; there is no `DELETE /api/admin/invite-codes/{id}`.
- Fail closed: an empty `fpl.admin.user-ids` refuses every `/api/admin/**` request with 403.
- Tests run from `backend/` with `set -a; source .env.test; set +a` first, against the local container (`scripts/test-db.sh up`). Never against Supabase.
- Commit subjects: one line, imperative, ~60 chars. Attribution trailer from the session reminder on every commit.
- Do not touch `InviteLedger.reserve`'s ordering or lock, and do not touch `SignupController`.

## File structure

| File | Responsibility |
|---|---|
| `backend/src/main/java/.../config/AdminAccess.java` (new) | Parses `fpl.admin.user-ids`; `isAdmin(UUID)`; the `AuthorizationManager` for `/api/admin/**`. |
| `backend/src/main/java/.../config/SecurityConfig.java` | One matcher line: `/api/admin/**` → `AdminAccess`, before `/api/**`. |
| `backend/src/main/java/.../user/UserProfileController.java` | `ProfileResponse` gains `admin`. |
| `backend/src/main/java/.../user/InviteCode.java` | `State` enum, `state(when)`, `note`, `createdAt`; `openAt`/`refusalReason` derive from `state`. |
| `backend/src/main/java/.../user/InviteLedger.java` | `summary()`, `summaryOf(id)`, `findById`, `setActive`, `setMaxUses`, `redemptionsOf`, `releaseBurnt`; `validFrom` on `create`. |
| `backend/src/main/java/.../user/InviteCodeAdminController.java` (new) | The endpoints; request/response records; validation → 400/404/409. |
| `backend/src/main/java/.../user/InviteCodeRunner.java` | Only the `create` call site (one more `null`). |
| `backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml` | `fpl.admin.user-ids`. |
| `backend/.env.supabase.example`, `backend/.env.test.example` | `ADMIN_USER_IDS`. |
| Tests: `config/AdminAccessTest.java`, `user/InviteCodeTest.java`, `user/InviteLedgerTest.java`, `user/InviteCodeAdminControllerTest.java` (all new), `user/UserProfileControllerTest.java` (one test added). |
| Docs: `docs/DEVELOPMENT.md`, `docs/EMAIL-SETUP.md`, `AGENTS.md`, `docs/STATUS.md`. |

`...` is `fi/karivanttinen/fplassistant`.

---

### Task 1: Admin identity — `AdminAccess`, the `/api/admin/**` matcher, `admin` on the profile

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/config/AdminAccess.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/config/SecurityConfig.java` (the `filterChain` bean, the `authorizeHttpRequests` block)
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/user/UserProfileController.java`
- Modify: `backend/src/main/resources/application.yml` (under `fpl:`), `backend/src/test/resources/application-test.yml` (under `fpl:`)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/config/AdminAccessTest.java` (new), `backend/src/test/java/fi/karivanttinen/fplassistant/user/UserProfileControllerTest.java`

**Interfaces:**
- Produces: `AdminAccess` bean with `boolean isAdmin(UUID userId)`; `AdminAccess.TEST_ADMIN` does not exist — the test profile pins the id `aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa` in `application-test.yml`, and later tests use that literal.
- Produces: `/api/admin/**` answers 401 with no token and 403 for a signed-in non-admin, before any controller runs.

**Step 1: Write the failing unit test**

`backend/src/test/java/fi/karivanttinen/fplassistant/config/AdminAccessTest.java`:

```java
package fi.karivanttinen.fplassistant.config;

import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who is an admin: the configured set, and nobody else. The empty set is the case that matters
 * most — an unset {@code ADMIN_USER_IDS} must close the admin API, not open it.
 */
class AdminAccessTest {

    private static final String ADMIN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String OTHER = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    @Test
    void anEmptySettingMakesNobodyAnAdmin() {
        AdminAccess access = new AdminAccess("");
        assertThat(access.isAdmin(UUID.fromString(ADMIN))).isFalse();
        assertThat(granted(access, jwtFor(ADMIN))).isFalse();
    }

    @Test
    void theListIsCommaSeparatedAndTolerantOfSpaces() {
        AdminAccess access = new AdminAccess(" " + ADMIN + " , " + OTHER + ", ");
        assertThat(access.isAdmin(UUID.fromString(ADMIN))).isTrue();
        assertThat(access.isAdmin(UUID.fromString(OTHER))).isTrue();
        assertThat(access.isAdmin(UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"))).isFalse();
    }

    @Test
    void aListedSubjectIsGrantedAndAnyOtherIsNot() {
        AdminAccess access = new AdminAccess(ADMIN);
        assertThat(granted(access, jwtFor(ADMIN))).isTrue();
        assertThat(granted(access, jwtFor(OTHER))).isFalse();
    }

    @Test
    void anAnonymousCallerIsNotAnAdmin() {
        AdminAccess access = new AdminAccess(ADMIN);
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        assertThat(granted(access, anonymous)).isFalse();
    }

    /** A verified token whose subject is not a UUID is refused, never a 500. */
    @Test
    void aSubjectThatIsNotAUuidIsRefused() {
        AdminAccess access = new AdminAccess(ADMIN);
        assertThat(granted(access, jwtFor("not-a-uuid"))).isFalse();
    }

    @Test
    void aMalformedIdInTheSettingFailsAtStartupNotAtRequestTime() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AdminAccess("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static boolean granted(AdminAccess access, Authentication authentication) {
        Supplier<Authentication> supplier = () -> authentication;
        AuthorizationResult result = access.authorize(supplier,
                new RequestAuthorizationContext(new org.springframework.mock.web.MockHttpServletRequest()));
        return result != null && result.isGranted();
    }

    private static Authentication jwtFor(String subject) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "ES256").subject(subject).build();
        return new JwtAuthenticationToken(jwt);
    }
}
```

**Step 2: Run it to verify it fails**

Run from `backend/`: `./gradlew test --tests "*AdminAccessTest"`
Expected: compilation failure, `AdminAccess` does not exist.

**Step 3: Implement `AdminAccess`**

`backend/src/main/java/fi/karivanttinen/fplassistant/config/AdminAccess.java`:

```java
package fi.karivanttinen.fplassistant.config;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Who may call {@code /api/admin/**}: the accounts named in {@code fpl.admin.user-ids}
 * ({@code ADMIN_USER_IDS}), a comma-separated list of JWT {@code sub} ids, and nobody else.
 *
 * <p>A configured set rather than a role claim or a profile column. One person is the whole
 * population, every other operational knob here is an environment variable, and neither the
 * database nor a Supabase setting can then grant admin — only a deploy-time secret can. The set
 * exists so it is not a single hard-coded id; if admins ever multiply, a claim on the token is
 * the cleaner mechanism and this class is the one place to swap it in.
 *
 * <p><b>Empty means nobody.</b> An unset variable closes the admin API rather than opening it,
 * the same posture as the missing service-role key closing sign-up. A malformed id fails the
 * context at startup: a typo that silently became "nobody" would look exactly like the secret
 * not being set.
 *
 * <p>Applied as the {@link AuthorizationManager} on the path in {@code SecurityConfig}, not per
 * controller, so a controller under {@code /api/admin} cannot forget it. An anonymous caller is
 * refused here and Spring turns that into the usual 401; a signed-in non-admin gets 403.
 */
@Component
public class AdminAccess implements AuthorizationManager<RequestAuthorizationContext> {

    private final Set<UUID> adminIds;

    public AdminAccess(@Value("${fpl.admin.user-ids:}") String configured) {
        this.adminIds = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(UUID userId) {
        return adminIds.contains(userId);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
                                         RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return new AuthorizationDecision(false);
        }
        try {
            return new AuthorizationDecision(isAdmin(UUID.fromString(jwt.getSubject())));
        } catch (IllegalArgumentException | NullPointerException e) {
            return new AuthorizationDecision(false);
        }
    }
}
```

**Step 4: Run the unit test**

Run: `./gradlew test --tests "*AdminAccessTest"`
Expected: PASS (6 tests).

**Step 5: Wire the matcher and the property**

In `SecurityConfig.filterChain`, change the signature to
`SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder jwtDecoder, AdminAccess adminAccess)`
and insert, immediately **before** the line `.requestMatchers("/api/**").authenticated()`:

```java
                        // KAR-54: the admin API is for the accounts named in ADMIN_USER_IDS and
                        // nobody else. On the path rather than in the controller, so a new
                        // endpoint under /api/admin cannot forget it. An empty set refuses
                        // everyone — see AdminAccess.
                        .requestMatchers("/api/admin/**").access(adminAccess)
```

`application.yml`, under `fpl:` (add after the `invite:` block, before `shortlist:`):

```yaml
  admin:
    # Who may call /api/admin/** — JWT sub ids, comma-separated. Empty (the default) means
    # nobody, which is the safe default: an unset secret closes the admin API rather than
    # opening it. Set with `fly secrets set ADMIN_USER_IDS=<uuid>`; the owner's sub is the id
    # column of the user in Supabase -> Authentication -> Users.
    user-ids: ${ADMIN_USER_IDS:}
```

`application-test.yml`, under `fpl:` (add after the `invite:` block):

```yaml
  admin:
    # The one admin the suite knows. InviteCodeAdminControllerTest and UserProfileControllerTest
    # mint tokens for this id; nothing else about it is special.
    user-ids: aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa
```

**Step 6: `admin` on the profile**

In `UserProfileController`:

```java
    /**
     * @param admin whether this account may call {@code /api/admin/**}. For the frontend to show
     *              or hide the link; the API refuses a non-admin regardless of what the page
     *              believes.
     */
    public record ProfileResponse(Integer fplTeamId, boolean admin) {}
```

Inject `AdminAccess adminAccess` through the constructor (a third field beside `profiles` and
`entries`) and make every `new ProfileResponse(...)` pass `adminAccess.isAdmin(userId)`, where
`userId` is `TokenSubject.userId(jwt)` read once at the top of each method:

```java
    @GetMapping
    public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = TokenSubject.userId(jwt);
        return new ProfileResponse(profiles.teamIdOf(userId).orElse(null), adminAccess.isAdmin(userId));
    }
```

and the same `adminAccess.isAdmin(userId)` second argument in `setTeamId` and `clearTeamId`
(read `userId` before the lookup in `setTeamId`, replacing the inline `TokenSubject.userId(jwt)`
in `profiles.setTeamId(...)`).

Add to `UserProfileControllerTest`:

```java
    private static final String ADMIN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    /** The page shows the admin link from this flag; the API refuses a non-admin regardless. */
    @Test
    void theProfileSaysWhetherTheCallerIsAnAdmin() throws Exception {
        readProfile(USER_A).andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false));
        readProfile(ADMIN).andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true));
    }
```

(`readProfile(String user)` already exists in that class — check its name with
`grep -n "private ResultActions" UserProfileControllerTest.java` and use whatever it is.)

**Step 7: Run the affected suites**

Run: `./gradlew test --tests "*AdminAccessTest" --tests "*UserProfileControllerTest" --tests "*JwtAuthenticationTest" --tests "*SignupControllerTest"`
Expected: all PASS. (`JwtAuthenticationTest` proves the chain still starts and 401s as before.)

**Step 8: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/config/AdminAccess.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/config/SecurityConfig.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/user/UserProfileController.java \
        backend/src/main/resources/application.yml backend/src/test/resources/application-test.yml \
        backend/src/test/java/fi/karivanttinen/fplassistant/config/AdminAccessTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/user/UserProfileControllerTest.java
git commit -m "Gate /api/admin/** on a configured set of admin user ids"
```

---

### Task 2: `InviteCode.state()` and the ledger's admin reads and writes

**Files:**
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteCode.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteLedger.java`
- Modify: `backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteCodeRunner.java:84` (one call site)
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteCodeTest.java` (new), `backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteLedgerTest.java` (new)

**Interfaces:**
- Produces on `InviteCode`: `enum State { OPEN, DISABLED, EXHAUSTED, NOT_YET_VALID, EXPIRED }`, `State state(OffsetDateTime when)`, two new trailing record components `String note, OffsetDateTime createdAt`.
- Produces on `InviteLedger`:
  - `record CodeSummary(InviteCode code, int accounts, int burnt, OffsetDateTime lastRedeemedAt)`
  - `record Summary(int accountCap, int placesTaken, int burnt, List<CodeSummary> codes)`
  - `record Redemption(long id, UUID userId, OffsetDateTime redeemedAt)`
  - `record Release(int released, List<String> reconciled)`
  - `class CeilingBelowUsesException extends RuntimeException`
  - `Summary summary()`, `Optional<CodeSummary> summaryOf(long id)`, `Optional<InviteCode> findById(long id)`, `boolean setActive(long id, boolean active)`, `Optional<InviteCode> setMaxUses(long id, Integer maxUses)` (throws `CeilingBelowUsesException`), `List<Redemption> redemptionsOf(long codeId)`, `Release releaseBurnt(Duration grace)`
  - `create(String code, String label, Integer maxUses, String benefitKind, Integer benefitValue, OffsetDateTime validFrom, OffsetDateTime validUntil, String note)` — `validFrom` is new, inserted before `validUntil`.

**Step 1: Failing unit test for `state()`**

`backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteCodeTest.java`:

```java
package fi.karivanttinen.fplassistant.user;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One rule for whether a code is open, read two ways: the state the admin page shows and the
 * refusal the person typing the code is told. They must never disagree, so both come from
 * {@link InviteCode#state}.
 */
class InviteCodeTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 13, 12, 0, 0, 0, ZoneOffset.UTC);

    private static InviteCode code(Integer maxUses, int uses, boolean active,
                                   OffsetDateTime validFrom, OffsetDateTime validUntil) {
        return new InviteCode(1, "launch", "launch", maxUses, uses, InviteCode.ACCESS, null,
                active, validFrom, validUntil, null, NOW.minusDays(1));
    }

    @Test
    void anOpenCodeHasNoRefusal() {
        InviteCode open = code(10, 3, true, null, null);
        assertThat(open.state(NOW)).isEqualTo(InviteCode.State.OPEN);
        assertThat(open.refusalReason(NOW)).isNull();
        assertThat(open.openAt(NOW)).isTrue();
    }

    @Test
    void disabledWinsOverEverythingElse() {
        InviteCode disabled = code(1, 1, false, NOW.plusDays(1), NOW.minusDays(1));
        assertThat(disabled.state(NOW)).isEqualTo(InviteCode.State.DISABLED);
        assertThat(disabled.refusalReason(NOW)).isEqualTo("That invite code is no longer in use.");
    }

    @Test
    void usedUpComesBeforeTheValidityWindow() {
        InviteCode full = code(5, 5, true, null, NOW.minusDays(1));
        assertThat(full.state(NOW)).isEqualTo(InviteCode.State.EXHAUSTED);
        assertThat(full.refusalReason(NOW)).isEqualTo("That invite code has been used up.");
        assertThat(full.remaining()).isZero();
    }

    @Test
    void notYetValidAndExpiredAreDistinct() {
        InviteCode early = code(null, 0, true, NOW.plusHours(1), null);
        InviteCode late = code(null, 0, true, null, NOW.minusHours(1));
        assertThat(early.state(NOW)).isEqualTo(InviteCode.State.NOT_YET_VALID);
        assertThat(early.refusalReason(NOW)).isEqualTo("That invite code is not valid yet.");
        assertThat(late.state(NOW)).isEqualTo(InviteCode.State.EXPIRED);
        assertThat(late.refusalReason(NOW)).isEqualTo("That invite code has expired.");
    }

    @Test
    void aCodeWithNoCeilingIsNeverExhausted() {
        InviteCode unlimited = code(null, 1_000, true, null, null);
        assertThat(unlimited.exhausted()).isFalse();
        assertThat(unlimited.remaining()).isNull();
        assertThat(unlimited.state(NOW)).isEqualTo(InviteCode.State.OPEN);
    }
}
```

**Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "*InviteCodeTest"`
Expected: compilation failure (`State` missing, constructor arity).

**Step 3: Implement on `InviteCode`**

Replace the record header and the `openAt`/`refusalReason` methods:

```java
/**
 * ... (keep the existing class Javadoc) ...
 * @param note free text for the owner — where the code was handed out, say
 * @param createdAt when the row was written
 */
public record InviteCode(long id, String code, String label, Integer maxUses, int uses,
                         String benefitKind, Integer benefitValue, boolean active,
                         OffsetDateTime validFrom, OffsetDateTime validUntil,
                         String note, OffsetDateTime createdAt) {

    /**
     * Why a code is closed, or {@link #OPEN}. Checked in this order: a disabled code is disabled
     * whatever else is true of it, and a used-up one is used up whether or not it has also
     * expired. The admin page shows this and the sign-up refusal is phrased from it, so the two
     * cannot drift apart.
     */
    public enum State { OPEN, DISABLED, EXHAUSTED, NOT_YET_VALID, EXPIRED }

    // keep ACCESS, DISCOUNT_PERCENT, remaining(), exhausted() as they are

    public State state(OffsetDateTime when) {
        if (!active) {
            return State.DISABLED;
        }
        if (exhausted()) {
            return State.EXHAUSTED;
        }
        if (validFrom != null && when.isBefore(validFrom)) {
            return State.NOT_YET_VALID;
        }
        if (validUntil != null && when.isAfter(validUntil)) {
            return State.EXPIRED;
        }
        return State.OPEN;
    }

    /** Whether the code is open at this instant. */
    public boolean openAt(OffsetDateTime when) {
        return state(when) == State.OPEN;
    }

    /** Why the code was refused, or null when it was not. Phrased for the person who typed it. */
    public String refusalReason(OffsetDateTime when) {
        return switch (state(when)) {
            case OPEN -> null;
            case DISABLED -> "That invite code is no longer in use.";
            case EXHAUSTED -> "That invite code has been used up.";
            case NOT_YET_VALID -> "That invite code is not valid yet.";
            case EXPIRED -> "That invite code has expired.";
        };
    }
}
```

Keep the existing Javadoc paragraph on `openAt` about "kept out of the SQL" — move it onto
`state`. In `InviteLedger.mapCode`, add the two trailing arguments:
`rs.getString("note"), rs.getObject("created_at", OffsetDateTime.class)`.

**Step 4: Run the unit test**

Run: `./gradlew test --tests "*InviteCodeTest"`
Expected: PASS (5 tests). `./gradlew compileJava` must also pass — `mapCode` is the only
constructor call site in main code (verified with `grep -rn "new InviteCode(" backend/src`).

**Step 5: Failing integration test for the ledger**

`backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteLedgerTest.java`:

```java
package fi.karivanttinen.fplassistant.user;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admin reads and writes on the ledger. Counts are asserted against rows the test seeds:
 * {@code uses} is the stored counter, {@code accounts} the rows with a user, {@code burnt} the
 * rows without one, and after a burnt place they are three different numbers.
 */
@SpringBootTest
@ActiveProfiles("test")
class InviteLedgerTest {

    @Autowired
    InviteLedger ledger;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${fpl.user.invite-code-table-name}")
    String codeTable;

    @Value("${fpl.user.invite-redemption-table-name}")
    String redemptionTable;

    @Value("${fpl.invite.max-accounts}")
    int accountCap;

    @BeforeEach
    @AfterEach
    void empty() {
        jdbc.update("DELETE FROM " + redemptionTable);
        jdbc.update("DELETE FROM " + codeTable);
    }

    private long seedCode(String code, Integer maxUses, int uses) {
        return jdbc.queryForObject("INSERT INTO " + codeTable
                + " (code, label, max_uses, uses) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, code, code, maxUses, uses);
    }

    private void seedRedemption(long codeId, UUID userId, OffsetDateTime at) {
        jdbc.update("INSERT INTO " + redemptionTable
                + " (code_id, code_label, user_id, redeemed_at) VALUES (?, ?, ?, ?)",
                codeId, "seed", userId, at);
    }

    @Test
    void theSummaryCountsAccountsAndBurntPlacesPerCodeAndOverall() {
        OffsetDateTime now = OffsetDateTime.now();
        long launch = seedCode("launch", 10, 3);
        long podcast = seedCode("podcast", null, 0);
        seedRedemption(launch, UUID.randomUUID(), now.minusDays(2));
        seedRedemption(launch, UUID.randomUUID(), now.minusDays(1));
        seedRedemption(launch, null, now.minusHours(1));

        InviteLedger.Summary summary = ledger.summary();

        assertThat(summary.accountCap()).isEqualTo(accountCap);
        assertThat(summary.placesTaken()).isEqualTo(3);
        assertThat(summary.burnt()).isEqualTo(1);
        assertThat(summary.codes()).extracting(s -> s.code().code()).containsExactly("launch", "podcast");

        InviteLedger.CodeSummary first = summary.codes().getFirst();
        assertThat(first.code().uses()).isEqualTo(3);
        assertThat(first.accounts()).isEqualTo(2);
        assertThat(first.burnt()).isEqualTo(1);
        assertThat(first.lastRedeemedAt()).isAfter(now.minusHours(2));

        InviteLedger.CodeSummary second = summary.codes().get(1);
        assertThat(second.accounts()).isZero();
        assertThat(second.burnt()).isZero();
        assertThat(second.lastRedeemedAt()).isNull();
        assertThat(ledger.summaryOf(podcast)).isPresent();
        assertThat(ledger.summaryOf(999_999)).isEmpty();
    }

    @Test
    void disablingAndEnablingFlipTheFlagAndReportAnUnknownId() {
        long id = seedCode("launch", 10, 0);

        assertThat(ledger.setActive(id, false)).isTrue();
        assertThat(ledger.findById(id).orElseThrow().active()).isFalse();
        assertThat(ledger.setActive(id, true)).isTrue();
        assertThat(ledger.findById(id).orElseThrow().active()).isTrue();
        assertThat(ledger.setActive(999_999, false)).isFalse();
    }

    @Test
    void theCeilingCanMoveDownToWhatIsSpentButNotBelowIt() {
        long id = seedCode("launch", 10, 4);

        assertThat(ledger.setMaxUses(id, 4).orElseThrow().remaining()).isZero();
        assertThatThrownBy(() -> ledger.setMaxUses(id, 3))
                .isInstanceOf(InviteLedger.CeilingBelowUsesException.class);
        assertThat(ledger.findById(id).orElseThrow().maxUses()).isEqualTo(4);
        assertThat(ledger.setMaxUses(id, null).orElseThrow().maxUses()).isNull();
        assertThat(ledger.setMaxUses(999_999, 5)).isEmpty();
    }

    @Test
    void redemptionsAreListedNewestFirstWithBurntPlacesAsNullUsers() {
        OffsetDateTime now = OffsetDateTime.now();
        long id = seedCode("launch", 10, 2);
        UUID older = UUID.randomUUID();
        seedRedemption(id, older, now.minusDays(1));
        seedRedemption(id, null, now.minusHours(1));

        List<InviteLedger.Redemption> rows = ledger.redemptionsOf(id);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).userId()).isNull();
        assertThat(rows.get(1).userId()).isEqualTo(older);
        assertThat(ledger.redemptionsOf(999_999)).isEmpty();
    }

    /** A young burnt row may be a sign-up whose Supabase call is still in flight. */
    @Test
    void releasingBurntPlacesSparesRecentOnesAndReconcilesTheCounter() {
        OffsetDateTime now = OffsetDateTime.now();
        long id = seedCode("launch", 10, 3);
        seedRedemption(id, UUID.randomUUID(), now.minusDays(1));
        seedRedemption(id, null, now.minusHours(1));
        seedRedemption(id, null, now.minusMinutes(1));

        InviteLedger.Release release = ledger.releaseBurnt(Duration.ofMinutes(10));

        assertThat(release.released()).isEqualTo(1);
        assertThat(release.reconciled()).containsExactly("launch");
        assertThat(ledger.findById(id).orElseThrow().uses()).isEqualTo(2);
        assertThat(ledger.summary().burnt()).isEqualTo(1);
    }

    @Test
    void createRecordsTheWholeValidityWindowAndTheNote() {
        OffsetDateTime from = OffsetDateTime.now().minusDays(1).withNano(0);
        OffsetDateTime until = from.plusDays(30);

        InviteCode created = ledger.create("Podcast", "The pod", 50, InviteCode.DISCOUNT_PERCENT,
                25, from, until, "read out in episode 12").orElseThrow();

        assertThat(created.code()).isEqualTo("Podcast");
        // Instants, not OffsetDateTimes: Postgres hands the value back in the session's offset,
        // and the JVM's default may differ. Same moment is what matters.
        assertThat(created.validFrom().toInstant()).isEqualTo(from.toInstant());
        assertThat(created.validUntil().toInstant()).isEqualTo(until.toInstant());
        assertThat(created.note()).isEqualTo("read out in episode 12");
        assertThat(created.createdAt()).isNotNull();
        assertThat(ledger.create("podcast", "again", 1, InviteCode.ACCESS, null, null, null, null))
                .isEmpty();
    }
}
```

**Step 6: Run it to verify it fails**

Run: `./gradlew test --tests "*InviteLedgerTest"`
Expected: compilation failure (`Summary`, `setActive`, … missing).

**Step 7: Implement on `InviteLedger`**

Add the imports `java.time.Duration` and keep the rest. Add these members after `Reservation`:

```java
    /** A ceiling lower than what the code has already spent. The caller answers 409. */
    public static class CeilingBelowUsesException extends RuntimeException {
        CeilingBelowUsesException(String message) {
            super(message);
        }
    }

    /**
     * One code with the counts the admin page shows beside it. {@code uses} on the code is the
     * stored counter the ceiling is checked against; {@code accounts} is the redemption rows with
     * a user and {@code burnt} the rows without one. They differ only after a burnt place, which
     * is why all three are shown.
     */
    public record CodeSummary(InviteCode code, int accounts, int burnt,
                              OffsetDateTime lastRedeemedAt) {}

    /**
     * The ledger at a glance. {@code placesTaken} is every redemption row, burnt included — the
     * number the account cap is checked against.
     */
    public record Summary(int accountCap, int placesTaken, int burnt, List<CodeSummary> codes) {}

    /** One redemption; {@code userId} is null for a burnt place. */
    public record Redemption(long id, UUID userId, OffsetDateTime redeemedAt) {}

    /** What releasing burnt places did: rows deleted, and the codes whose counter moved. */
    public record Release(int released, List<String> reconciled) {}
```

and these methods after `create`:

```java
    public Summary summary() {
        Integer taken = jdbc.queryForObject("SELECT count(*) FROM " + redemptionTable,
                Integer.class);
        Integer burnt = jdbc.queryForObject(
                "SELECT count(*) FROM " + redemptionTable + " WHERE user_id IS NULL",
                Integer.class);
        return new Summary(maxAccounts, taken == null ? 0 : taken, burnt == null ? 0 : burnt,
                summaries("", new Object[0]));
    }

    public Optional<CodeSummary> summaryOf(long id) {
        List<CodeSummary> rows = summaries("WHERE c.id = ?", new Object[] {id});
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    // One query, not one per code: count(user_id) counts the rows with a user, count(*) all of
    // them, and the difference is the burnt places.
    private List<CodeSummary> summaries(String where, Object[] args) {
        return jdbc.query("""
                SELECT c.*,
                       COALESCE(r.accounts, 0) AS accounts,
                       COALESCE(r.burnt, 0) AS burnt,
                       r.last_redeemed_at
                  FROM %s c
                  LEFT JOIN (SELECT code_id,
                                    count(user_id) AS accounts,
                                    count(*) - count(user_id) AS burnt,
                                    max(redeemed_at) AS last_redeemed_at
                               FROM %s
                              GROUP BY code_id) r ON r.code_id = c.id
                 %s
                 ORDER BY c.created_at, c.id
                """.formatted(codeTable, redemptionTable, where),
                (rs, rowNum) -> new CodeSummary(mapCode(rs, rowNum), rs.getInt("accounts"),
                        rs.getInt("burnt"), rs.getObject("last_redeemed_at", OffsetDateTime.class)),
                args);
    }

    public Optional<InviteCode> findById(long id) {
        List<InviteCode> rows = jdbc.query("SELECT * FROM " + codeTable + " WHERE id = ?",
                InviteLedger::mapCode, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Opens or closes a code. False when no such code exists. Idempotent. */
    public boolean setActive(long id, boolean active) {
        return jdbc.update("UPDATE " + codeTable + " SET active = ? WHERE id = ?", active, id) == 1;
    }

    /**
     * Moves a code's ceiling; null removes it. Under the sign-up lock, so a reservation cannot
     * slip between the read and the write, and the CHECK constraint stays a backstop rather than
     * the error the caller sees.
     *
     * @throws CeilingBelowUsesException when the ceiling would be below what is already spent —
     *         the code should be disabled instead, and the message says so
     */
    @Transactional
    public Optional<InviteCode> setMaxUses(long id, Integer maxUses) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                redemptionTable);
        Optional<InviteCode> current = findById(id);
        if (current.isEmpty()) {
            return current;
        }
        if (maxUses != null && maxUses < current.get().uses()) {
            throw new CeilingBelowUsesException("The code " + current.get().code() + " has already"
                    + " used " + current.get().uses() + " places; disable it rather than setting"
                    + " a ceiling below that.");
        }
        jdbc.update("UPDATE " + codeTable + " SET max_uses = ? WHERE id = ?", maxUses, id);
        return findById(id);
    }

    /** The places a code has given out, newest first. Burnt places carry no user. */
    public List<Redemption> redemptionsOf(long codeId) {
        return jdbc.query("SELECT id, user_id, redeemed_at FROM " + redemptionTable
                        + " WHERE code_id = ? ORDER BY redeemed_at DESC, id DESC",
                (rs, rowNum) -> new Redemption(rs.getLong("id"),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("redeemed_at", OffsetDateTime.class)),
                codeId);
    }

    /**
     * Deletes burnt places older than {@code grace} and brings every counter back in line — the
     * two-statement procedure from {@code docs/DEVELOPMENT.md} as one call, under the sign-up
     * lock. A burnt row younger than the grace period is left alone: it may be a sign-up whose
     * Supabase call has not yet answered, and deleting it would let that account through
     * uncounted.
     */
    @Transactional
    public Release releaseBurnt(Duration grace) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                redemptionTable);
        int released = jdbc.update("DELETE FROM " + redemptionTable
                        + " WHERE user_id IS NULL AND redeemed_at < ?",
                OffsetDateTime.now().minus(grace));
        return new Release(released, reconcile());
    }
```

Change `create` to take `validFrom` before `validUntil` and write both columns:

```java
    public Optional<InviteCode> create(String code, String label, Integer maxUses,
                                       String benefitKind, Integer benefitValue,
                                       OffsetDateTime validFrom, OffsetDateTime validUntil,
                                       String note) {
        if (findByCode(code).isPresent()) {
            return Optional.empty();
        }
        jdbc.update("""
                INSERT INTO %s (code, label, max_uses, benefit_kind, benefit_value, valid_from,
                                valid_until, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(codeTable),
                code.trim(), label, maxUses, benefitKind, benefitValue, validFrom, validUntil, note);
        return findByCode(code);
    }
```

and in `InviteCodeRunner.create` change the call to
`ledger.create(code, code, maxUses, benefitKind, benefitValue, null, null, null)`.

Update the class Javadoc paragraph that says "Releasing one is a statement … and then re-running
reconcile": append one sentence — "Since KAR-54 that is `releaseBurnt`, reached through
`POST /api/admin/invite-codes/release-burnt`."

**Step 8: Run the ledger and sign-up suites**

Run: `./gradlew test --tests "*InviteLedgerTest" --tests "*InviteCodeTest" --tests "*SignupControllerTest"`
Expected: all PASS. Sign-up behaviour is unchanged: `reserve` still checks `exhausted()` before
`refusalReason`, so a full code still answers 409 through `CapReachedException`.

**Step 9: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteCode.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteLedger.java \
        backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteCodeRunner.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteCodeTest.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteLedgerTest.java
git commit -m "Add admin reads and writes to the invite ledger"
```

---

### Task 3: `InviteCodeAdminController`

**Files:**
- Create: `backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteCodeAdminController.java`
- Test: `backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteCodeAdminControllerTest.java` (new)

**Interfaces:**
- Consumes: everything Task 2 produces on `InviteLedger` and `InviteCode.State`; the Task 1 guard (the controller does **no** admin check of its own — the path does it).
- Produces: the endpoints below, JSON shapes as in the records.

| Method and path | Body | Answers |
|---|---|---|
| `GET /api/admin/invite-codes` | — | 200 `ListResponse` |
| `POST /api/admin/invite-codes` | `CreateRequest` | 201 `CodeView`; 400 invalid; 409 exists |
| `POST /api/admin/invite-codes/{id}/disable` | — | 200 `CodeView`; 404 |
| `POST /api/admin/invite-codes/{id}/enable` | — | 200 `CodeView`; 404 |
| `PUT /api/admin/invite-codes/{id}/max-uses` | `{"maxUses": n}` | 200 `CodeView`; 400 `n < 1` or missing; 404; 409 below uses |
| `DELETE /api/admin/invite-codes/{id}/max-uses` | — | 200 `CodeView` (no ceiling); 404 |
| `GET /api/admin/invite-codes/{id}/redemptions` | — | 200 `[RedemptionView]`; 404 |
| `POST /api/admin/invite-codes/release-burnt` | — | 200 `ReleaseResponse` |

**Step 1: Failing controller test**

`backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteCodeAdminControllerTest.java`:

```java
package fi.karivanttinen.fplassistant.user;

import java.time.OffsetDateTime;
import java.util.UUID;

import fi.karivanttinen.fplassistant.testsupport.LocalSigningKey;
import fi.karivanttinen.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin API: who may call it, and that what it changes is what sign-up then enforces.
 *
 * <p>Two requests here go to {@code /api/auth/signup}, which is rate limited per IP at 20 a
 * minute and shares that budget with {@code SignupControllerTest} only if the two classes share
 * a context — they do not (this one imports {@code LocalSigningKey}). Keep the sign-up calls in
 * this class in single digits regardless.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class InviteCodeAdminControllerTest {

    /** The id application-test.yml names in fpl.admin.user-ids. */
    private static final String ADMIN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String NOT_ADMIN = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String BASE = "/api/admin/invite-codes";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SupabaseAdminClient admin;

    @Value("${fpl.user.invite-code-table-name}")
    String codeTable;

    @Value("${fpl.user.invite-redemption-table-name}")
    String redemptionTable;

    @BeforeEach
    @AfterEach
    void empty() {
        jdbc.update("DELETE FROM " + redemptionTable);
        jdbc.update("DELETE FROM " + codeTable);
    }

    private long seedCode(String code, Integer maxUses, int uses) {
        return jdbc.queryForObject("INSERT INTO " + codeTable
                + " (code, label, max_uses, uses) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, code, code, maxUses, uses);
    }

    private void seedRedemption(long codeId, UUID userId, OffsetDateTime at) {
        jdbc.update("INSERT INTO " + redemptionTable
                + " (code_id, code_label, user_id, redeemed_at) VALUES (?, ?, ?, ?)",
                codeId, "seed", userId, at);
    }

    private static MockHttpServletRequestBuilder as(String user, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + TestJwks.validTokenFor(user));
    }

    private ResultActions signUp(String code) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"password\":\"correct horse battery"
                        + " staple\",\"inviteCode\":\"" + code + "\"}"));
    }

    @Test
    void withoutATokenItIs401AndAsANonAdminItIs403() throws Exception {
        seedCode("launch", 10, 0);
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mockMvc.perform(as(NOT_ADMIN, get(BASE))).andExpect(status().isForbidden());
        mockMvc.perform(as(NOT_ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"sneaky\",\"maxUses\":1}")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + codeTable, Integer.class))
                .isEqualTo(1);
    }

    @Test
    void theListCarriesCountsAndAServerSideState() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long launch = seedCode("launch", 10, 3);
        seedRedemption(launch, UUID.randomUUID(), now.minusDays(1));
        seedRedemption(launch, UUID.randomUUID(), now.minusHours(2));
        seedRedemption(launch, null, now.minusHours(1));
        long full = seedCode("full", 1, 1);
        jdbc.update("UPDATE " + codeTable + " SET active = false WHERE code = 'full'");
        seedCode("open", null, 0);

        mockMvc.perform(as(ADMIN, get(BASE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCap").value(2))
                .andExpect(jsonPath("$.placesTaken").value(3))
                .andExpect(jsonPath("$.burnt").value(1))
                .andExpect(jsonPath("$.codes", hasSize(3)))
                .andExpect(jsonPath("$.codes[0].code").value("launch"))
                .andExpect(jsonPath("$.codes[0].uses").value(3))
                .andExpect(jsonPath("$.codes[0].remaining").value(7))
                .andExpect(jsonPath("$.codes[0].accounts").value(2))
                .andExpect(jsonPath("$.codes[0].burnt").value(1))
                .andExpect(jsonPath("$.codes[0].state").value("OPEN"))
                .andExpect(jsonPath("$.codes[0].lastRedeemedAt").isNotEmpty())
                .andExpect(jsonPath("$.codes[1].id").value(full))
                .andExpect(jsonPath("$.codes[1].state").value("DISABLED"))
                .andExpect(jsonPath("$.codes[2].maxUses").doesNotExist())
                .andExpect(jsonPath("$.codes[2].remaining").doesNotExist())
                .andExpect(jsonPath("$.codes[2].state").value("OPEN"));
    }

    @Test
    void creatingACodeAnswers201AndADuplicateInAnyCaseAnswers409() throws Exception {
        mockMvc.perform(as(ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\" Podcast \",\"maxUses\":50,\"benefitKind\":\"discount_percent\","
                        + "\"benefitValue\":25,\"validUntil\":\"2027-06-01T00:00:00Z\","
                        + "\"note\":\"episode 12\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("Podcast"))
                .andExpect(jsonPath("$.label").value("Podcast"))
                .andExpect(jsonPath("$.maxUses").value(50))
                .andExpect(jsonPath("$.remaining").value(50))
                .andExpect(jsonPath("$.benefitKind").value("discount_percent"))
                .andExpect(jsonPath("$.benefitValue").value(25))
                .andExpect(jsonPath("$.note").value("episode 12"))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.accounts").value(0));

        mockMvc.perform(as(ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"podcast\",\"maxUses\":1}")))
                .andExpect(status().isConflict());
    }

    @Test
    void anInvalidCodeRequestIs400AndWritesNothing() throws Exception {
        String[] bodies = {
                "{\"code\":\"  \",\"maxUses\":1}",
                "{\"code\":\"x\",\"maxUses\":0}",
                "{\"code\":\"x\",\"maxUses\":1,\"benefitKind\":\"discount_percent\"}",
                "{\"code\":\"x\",\"maxUses\":1,\"benefitKind\":\"discount_percent\",\"benefitValue\":101}",
                "{\"code\":\"x\",\"maxUses\":1,\"benefitKind\":\"free_forever\"}",
                "{\"code\":\"x\",\"maxUses\":1,\"validFrom\":\"2027-01-02T00:00:00Z\","
                        + "\"validUntil\":\"2027-01-01T00:00:00Z\"}",
        };
        for (String body : bodies) {
            mockMvc.perform(as(ADMIN, post(BASE).contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + codeTable, Integer.class)).isZero();
    }

    /** The requirement: switched off in one call, and sign-up refuses it on the next attempt. */
    @Test
    void disablingACodeStopsSignUpAndEnablingRestoresIt() throws Exception {
        when(admin.configured()).thenReturn(true);
        long id = seedCode("launch", 10, 0);

        mockMvc.perform(as(ADMIN, post(BASE + "/" + id + "/disable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.state").value("DISABLED"));
        signUp("launch").andExpect(status().isForbidden());
        verify(admin, never()).createUser(any(), any());

        mockMvc.perform(as(ADMIN, post(BASE + "/" + id + "/enable")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.state").value("OPEN"));
        // Refused for the configured-but-unavailable Supabase, not for the code: the peek passed.
        when(admin.createUser(any(), any())).thenReturn(new SupabaseAdminClient.Unavailable());
        signUp("launch").andExpect(status().isServiceUnavailable());

        mockMvc.perform(as(ADMIN, post(BASE + "/999999/disable"))).andExpect(status().isNotFound());
    }

    @Test
    void theCeilingIsSetRemovedAndRefusedBelowWhatIsSpent() throws Exception {
        long id = seedCode("launch", 10, 4);

        mockMvc.perform(as(ADMIN, put(BASE + "/" + id + "/max-uses")
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxUses\":4}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUses").value(4))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.state").value("EXHAUSTED"));
        mockMvc.perform(as(ADMIN, put(BASE + "/" + id + "/max-uses")
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxUses\":3}")))
                .andExpect(status().isConflict());
        mockMvc.perform(as(ADMIN, put(BASE + "/" + id + "/max-uses")
                .contentType(MediaType.APPLICATION_JSON).content("{\"maxUses\":0}")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(ADMIN, put(BASE + "/" + id + "/max-uses")
                .contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(as(ADMIN, delete(BASE + "/" + id + "/max-uses")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxUses").doesNotExist())
                .andExpect(jsonPath("$.state").value("OPEN"));
        mockMvc.perform(as(ADMIN, delete(BASE + "/999999/max-uses"))).andExpect(status().isNotFound());
    }

    @Test
    void redemptionsAreTheCodesAttributionNewestFirst() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long id = seedCode("launch", 10, 2);
        UUID user = UUID.randomUUID();
        seedRedemption(id, user, now.minusDays(1));
        seedRedemption(id, null, now.minusHours(1));

        mockMvc.perform(as(ADMIN, get(BASE + "/" + id + "/redemptions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].userId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].userId").value(user.toString()));
        mockMvc.perform(as(ADMIN, get(BASE + "/999999/redemptions"))).andExpect(status().isNotFound());
    }

    @Test
    void releasingBurntPlacesReportsWhatItDid() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        long id = seedCode("launch", 10, 2);
        seedRedemption(id, null, now.minusHours(1));
        seedRedemption(id, null, now.minusMinutes(1));

        mockMvc.perform(as(ADMIN, post(BASE + "/release-burnt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(1))
                .andExpect(jsonPath("$.reconciled[0]").value("launch"));
        assertThat(jdbc.queryForObject("SELECT uses FROM " + codeTable + " WHERE id = ?",
                Integer.class, id)).isEqualTo(1);
    }
}
```

Check that `SupabaseAdminClient.Unavailable` is a record with a no-arg constructor
(`grep -n "record Unavailable\|Unavailable" SupabaseAdminClient.java`); if it is an enum or
singleton, construct it the way `SignupControllerTest.anUnreachableSupabaseIsAServiceProblemRatherThanARejection` does.

**Step 2: Run it to verify it fails**

Run: `./gradlew test --tests "*InviteCodeAdminControllerTest"`
Expected: the 401/403 test passes already (the guard is on the path, and 403 comes from the
guard before any 404) — the rest fail with 404/403-or-405 because no controller exists.

**Step 3: Implement the controller**

`backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteCodeAdminController.java`:

```java
package fi.karivanttinen.fplassistant.user;

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
```

Note `"/release-burnt"` is a `POST` and `"/{id}/disable"` is `POST /{id}/…`; the two cannot
collide because `release-burnt` has one segment and `{id}/disable` two. `GET /{id}/redemptions`
with a non-numeric id answers 400 from Spring's conversion, which is fine.

**Step 4: Run the controller test**

Run: `./gradlew test --tests "*InviteCodeAdminControllerTest"`
Expected: PASS (8 tests). If `$.codes[2].maxUses` is present as `null`, the `@JsonInclude` on
`CodeView` is missing — it must be on the record.

**Step 5: Commit**

```bash
git add backend/src/main/java/fi/karivanttinen/fplassistant/user/InviteCodeAdminController.java \
        backend/src/test/java/fi/karivanttinen/fplassistant/user/InviteCodeAdminControllerTest.java
git commit -m "Add the invite-code admin API"
```

---

### Task 4: Configuration examples, docs, status, full suite

**Files:**
- Modify: `backend/.env.supabase.example` (after `INVITE_MAX_ACCOUNTS=30`), `backend/.env.test.example` (end of file)
- Modify: `docs/DEVELOPMENT.md` (section "The free tier and sign-up", lines ~95–135)
- Modify: `docs/EMAIL-SETUP.md` (section 5.1, "Check whether a code exists, and create one")
- Modify: `AGENTS.md` (Conventions list, after the "No `@Tool` method takes a parameter that identifies an account" bullet)
- Modify: `docs/STATUS.md` (append at the end)
- Modify: this plan's `**PROGRESS:**` line and an `## Outcome` section at the end

**Step 1: Env examples**

`backend/.env.supabase.example`, after the `INVITE_MAX_ACCOUNTS=30` line:

```bash

# Who may call the admin API (/api/admin/**): JWT sub ids, comma-separated. The owner's sub is
# the id column in Supabase -> Authentication -> Users. Empty means nobody, which closes the
# admin API rather than opening it. Set in production with `fly secrets set ADMIN_USER_IDS=…`.
ADMIN_USER_IDS=
```

`backend/.env.test.example`, at the end:

```bash

# Not read by the suite: application-test.yml pins fpl.admin.user-ids to a fixed id so the admin
# API tests can mint a token for it. Listed so the variable is known.
ADMIN_USER_IDS=
```

**Step 2: `docs/DEVELOPMENT.md`**

In "The free tier and sign-up", replace the paragraph beginning "Codes are rows, not a secret
(since PR #52). Create one with the CLI…" and the paragraph plus SQL block beginning "A failed
Supabase call leaves a reserved row…" with:

````markdown
Codes are rows, not a secret (since PR #52), and since KAR-54 they are operated through the admin
API rather than SQL. Who may call it is `ADMIN_USER_IDS` (`fpl.admin.user-ids`): the owner's JWT
`sub`, comma-separated if there is ever more than one, and **empty means nobody** — an unset
variable closes `/api/admin/**` with 403. `GET /api/profile` reports `admin` so the page knows
whether to show the link; the API refuses a non-admin regardless.

| Call | Does |
|---|---|
| `GET /api/admin/invite-codes` | every code with `uses`, `accounts`, `burnt`, `lastRedeemedAt` and a server-computed `state` (`OPEN`, `DISABLED`, `EXHAUSTED`, `NOT_YET_VALID`, `EXPIRED`), plus `accountCap`, `placesTaken`, `burnt` overall |
| `POST /api/admin/invite-codes` | `{code, label?, maxUses?, benefitKind?, benefitValue?, validFrom?, validUntil?, note?}` → 201; 409 if the code exists in any case |
| `POST …/{id}/disable`, `POST …/{id}/enable` | switch a code off or on; sign-up refuses a disabled code on its next attempt |
| `PUT …/{id}/max-uses` `{maxUses}`, `DELETE …/{id}/max-uses` | move or remove the ceiling; 409 below what is already spent — disable the code instead |
| `GET …/{id}/redemptions` | who came in on the code, newest first; a null `userId` is a burnt place |
| `POST …/release-burnt` | deletes burnt places older than ten minutes and reconciles every counter |

Codes are never deleted: redemption rows point at them. With `curl`, a token from the browser's
session and the admin's account:

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://fpl-sidekick.com/api/admin/invite-codes | jq .
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"code":"podcast","maxUses":50}' https://fpl-sidekick.com/api/admin/invite-codes
```

The CLI (`--create-invite-code=launch:10`, `--list-invite-codes`, `--reconcile-invite-codes`)
still works locally against `.env.supabase`; `docs/EMAIL-SETUP.md` 5.1 explains why it must not
run on the production machine. The `INVITE_CODE` Fly secret still exists and is ignored.

A failed Supabase call leaves a reserved row with a null `user_id` — one burnt place, chosen over
an account nobody counted (see `InviteLedger`). `POST /api/admin/invite-codes/release-burnt` is
the documented way to release them; the SQL it runs, for a database you can reach directly:

```sql
DELETE FROM fpl_invite_redemption WHERE user_id IS NULL AND redeemed_at < now() - interval '10 minutes';
UPDATE fpl_invite_code c
   SET uses = (SELECT count(*) FROM fpl_invite_redemption r WHERE r.code_id = c.id)
 WHERE c.uses <> (SELECT count(*) FROM fpl_invite_redemption r WHERE r.code_id = c.id);
```
````

Also in the "Secrets" section's table of environment variables (if one lists
`INVITE_MAX_ACCOUNTS`; check with `grep -n INVITE_MAX_ACCOUNTS docs/DEVELOPMENT.md`), add a row
for `ADMIN_USER_IDS` in the same style. If there is no such table, skip this.

**Step 3: `docs/EMAIL-SETUP.md` 5.1**

After the paragraph "Use the Supabase SQL editor instead. Reading is free:" and before its SQL
block, insert:

```markdown
Since KAR-54 the admin API is the intended path — `POST /api/admin/invite-codes` with the
owner's token, once `ADMIN_USER_IDS` is set (`docs/DEVELOPMENT.md`, "The free tier and sign-up").
The SQL below remains correct and is what the API does.
```

**Step 4: `AGENTS.md`**

After the bullet that begins "**No `@Tool` method takes a parameter that identifies an
account.**", add:

```markdown
- **`/api/admin/**` is admin-only, decided on the path.** `AdminAccess` is the
  `AuthorizationManager` `SecurityConfig` attaches to that matcher; it holds the JWT `sub` ids in
  `fpl.admin.user-ids` (`ADMIN_USER_IDS`) and **an empty set refuses everyone**. A controller
  under that path carries no check of its own and takes no user id. Invite codes are operated
  through `InviteCodeAdminController` (KAR-54): a code's `state` is computed by
  `InviteCode.state`, which `refusalReason` is written on, so the admin view and the sign-up
  refusal cannot disagree. Codes are disabled, never deleted — redemption rows point at them.
```

**Step 5: `docs/STATUS.md`**

Append:

```markdown

## Invite-code admin API (KAR-54, 2026-09-13)

Plan: `docs/superpowers/plans/2026-09-13-kar-54-invite-code-admin-api.md`. The page that uses it is KAR-55.

Per-code ceilings, the `active` flag and per-code attribution have existed since PR #52; what did
not exist was any way to operate them except SQL in the Supabase editor, because the CLI must not
run on the production machine (`docs/EMAIL-SETUP.md` 5.1). `/api/admin/invite-codes` now lists
every code with its counts and a server-computed state, creates codes, disables and enables them,
moves or removes a ceiling, lists a code's redemptions and releases burnt places (older than ten
minutes, then reconciles — KAR-18's procedure as one call).

**Admin identity is `ADMIN_USER_IDS`**, a comma-separated set of JWT `sub` ids, enforced by
`AdminAccess` on the `/api/admin/**` matcher in `SecurityConfig` rather than in the controller.
Empty means nobody, the same fail-closed posture as the missing service-role key. Chosen over a
Supabase `app_metadata` claim (cleaner if admins multiply, but a token round-trip and a dashboard
setting for a population of one) and over a profile column (a table write that grants admin).
`GET /api/profile` reports `admin` for the frontend's link; the API refuses regardless.

`InviteCode.state()` is the one rule for whether a code is open, and `refusalReason()` is now
written on it, so the list can never show OPEN for a code sign-up would refuse. Codes are never
deleted. `InviteLedger.reserve` and `SignupController` are untouched.

**Not set in production yet:** `fly secrets set ADMIN_USER_IDS=<owner sub>` — until then every
admin call answers 403.

Verified: `./gradlew test` green (see the plan's Outcome for the count), including the new
`AdminAccessTest`, `InviteCodeTest`, `InviteLedgerTest` and `InviteCodeAdminControllerTest`.
```

**Step 6: Full suite**

From `backend/`, with `.env.test` loaded and the container up (`scripts/test-db.sh up`):

Run: `./gradlew test`
Expected: green. Record the total test count from the Gradle output (or
`build/reports/tests/test/index.html`) into this plan's Outcome and into the STATUS entry's
"Verified" line.

**Step 7: Plan outcome and commit**

Set `**PROGRESS:** Task 4/4 done.` and append to this plan:

```markdown

## Outcome

Tasks 1–4 done. Deviations from the plan: (list any, or "none"). Full suite: N tests, 0 failures.
```

```bash
git add backend/.env.supabase.example backend/.env.test.example docs/DEVELOPMENT.md \
        docs/EMAIL-SETUP.md AGENTS.md docs/STATUS.md \
        docs/superpowers/plans/2026-09-13-kar-54-invite-code-admin-api.md
git commit -m "Document the invite-code admin API and ADMIN_USER_IDS"
```

---

## Self-review against KAR-54

- Admin identity, path-level enforcement, empty-set refusal, `admin` on the profile → Task 1.
- List with counts and server-side `state`, create with validation and 409, disable/enable,
  max-uses set/remove with 409 below uses, redemptions, release-burnt with grace and reconcile,
  no DELETE → Tasks 2–3.
- Ledger methods named in the ticket (`summary`, `setActive`, `setMaxUses`, `redemptionsOf`,
  `releaseBurnt`, `validFrom` on `create`) → Task 2; `summaryOf` and `findById` added as
  support.
- Tests named in the ticket → `InviteCodeAdminControllerTest`, `AdminAccessTest` (empty-set
  case), `UserProfileControllerTest` addition, `InviteCodeTest`; `InviteLedgerTest` added.
- Docs and config → Task 4. Type names are consistent across tasks (`CodeSummary`, `Summary`,
  `Redemption`, `Release`, `CeilingBelowUsesException`, `InviteCode.State`, `CodeView`).
- Not rate limited, never a `@Tool`, no user id in any admin signature — by construction.

## Outcome

Tasks 1–4 done. Deviations from the plan:

- Task 1 needed a fix round after Task 3's full-suite run: `MyTeamControllerTest`, a
  `@WebMvcTest` slice importing `SecurityConfig`, lacked the new `AdminAccess` bean; fixed in
  735f4bb by adding it to the slice's `@Import`.
- A plan edit before Task 2: the ledger validity test compares instants rather than
  `OffsetDateTime`s.
- Fix wave 2: the pre-push hook's fresh-database run refused the push with 11 failures, all in
  `UserProfileControllerTest`, "sorry, too many clients already" — `InviteCodeAdminControllerTest`
  (`@Import(LocalSigningKey.class)` plus a mocked `SupabaseAdminClient`, a context no other class
  shares) was one more cached connection pool crossing the KAR-51 ceiling. It now carries
  `@DirtiesContext(AFTER_CLASS)`, the same KAR-42 workaround `SecurityHeadersTest` uses; the real
  pool-size fix is still KAR-51's.

Full suite: 942 tests, 0 real failures. The full run showed 11 failures, all in
`UserProfileControllerTest`, all `Failed to obtain JDBC Connection` / "sorry, too many clients
already" — connection-pool exhaustion (KAR-51), not a regression from this work; that class run
alone (`./gradlew test --tests "*UserProfileControllerTest"`) is green. After the fix-wave-2
`@DirtiesContext` change: `../scripts/test-db.sh reset && ./gradlew cleanTest test`, 942 tests,
0 failures, 0 errors.
