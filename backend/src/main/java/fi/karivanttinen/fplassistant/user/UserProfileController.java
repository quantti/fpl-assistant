package fi.karivanttinen.fplassistant.user;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's own profile. The user id comes from the verified token's {@code sub} claim and
 * from nowhere else — there is no path parameter, and no request body field, that names a user.
 *
 * <p>The FPL team id is optional: chat works fully without it, and nothing in this phase reads
 * it. It is stored now so later phases can answer questions about the user's own squad.
 *
 * <p>Setting and clearing are separate verbs on purpose. With one nullable PUT, a body with a
 * missing or misspelled field binds to {@code null} and silently wipes a stored id while
 * answering 200 — the destructive path would be the one you reach by sending nothing.
 */
@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

    private final UserProfileService profiles;
    private final FplEntryLookup entries;

    public UserProfileController(UserProfileService profiles, FplEntryLookup entries) {
        this.profiles = profiles;
        this.entries = entries;
    }

    public record ProfileResponse(Integer fplTeamId) {}

    public record TeamIdRequest(Integer fplTeamId) {}

    @GetMapping
    public ProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        return new ProfileResponse(profiles.teamIdOf(userId(jwt)).orElse(null));
    }

    @PutMapping("/fpl-team-id")
    public ProfileResponse setTeamId(@AuthenticationPrincipal Jwt jwt,
                                     @RequestBody TeamIdRequest request) {
        Integer teamId = request.fplTeamId();
        if (teamId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "fplTeamId is required. To remove a stored id, DELETE this resource.");
        }
        if (teamId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "An FPL team id is a positive number — find yours in the URL of your points"
                            + " page on fantasy.premierleague.com.");
        }

        // A switch expression, not a statement: a fourth Result constant must not fall through
        // to "store it" — which is the one outcome this whole lookup exists to prevent.
        String rejection = switch (entries.lookup(teamId)) {
            case EXISTS -> null;
            case NOT_FOUND -> "FPL does not know a team with id " + teamId + ".";
            case UNAVAILABLE -> "unavailable";
        };
        // 400 and 503 rather than one error: "that team does not exist" sends the user looking
        // for a typo, and saying it while FPL is down sends them looking for a mistake they did
        // not make.
        if ("unavailable".equals(rejection)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not reach FPL to check that team id. Nothing was saved — please try"
                            + " again shortly.");
        }
        if (rejection != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, rejection);
        }

        profiles.setTeamId(userId(jwt), teamId);
        return new ProfileResponse(teamId);
    }

    @DeleteMapping("/fpl-team-id")
    public ProfileResponse clearTeamId(@AuthenticationPrincipal Jwt jwt) {
        profiles.setTeamId(userId(jwt), null);
        return new ProfileResponse(null);
    }

    /**
     * The token is verified by the time this runs, but "verified" does not mean the {@code sub}
     * claim is a UUID — a token without one, or with a different subject format, would otherwise
     * become a 500 with a stack trace. {@code SecurityConfig} goes to some lengths to keep
     * attacker-reachable token problems at 401; this keeps that true one layer up.
     */
    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "The token carries no usable subject claim.");
        }
    }
}
