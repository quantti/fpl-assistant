package fi.karivanttinen.fplassistant.user;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
        Integer teamId = request == null ? null : request.fplTeamId();

        if (teamId != null) {
            if (teamId <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "An FPL team id is a positive number — find yours in the URL of your"
                                + " points page on fantasy.premierleague.com.");
            }
            switch (entries.lookup(teamId)) {
                // 400 and 503 rather than one error: "that team does not exist" sends the user
                // looking for a typo, and saying it while FPL is down sends them looking for a
                // mistake they did not make.
                case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "FPL does not know a team with id " + teamId + ".");
                case UNAVAILABLE -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Could not reach FPL to check that team id. Nothing was saved — please"
                                + " try again shortly.");
                case EXISTS -> { }
            }
        }

        profiles.setTeamId(userId(jwt), teamId);
        return new ProfileResponse(teamId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
