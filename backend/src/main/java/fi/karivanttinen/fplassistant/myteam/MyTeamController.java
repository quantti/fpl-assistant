package fi.karivanttinen.fplassistant.myteam;

import fi.karivanttinen.fplassistant.config.TokenSubject;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's read-only squad page. Identity comes only from the verified token; no
 * request parameter can select another account or FPL entry.
 */
@RestController
@RequestMapping("/api/my-team")
public class MyTeamController {

    private final MyTeamService myTeam;
    private final int allowance;

    public MyTeamController(MyTeamService myTeam,
                            @Value("${fpl.myteam.max-requests-per-turn}") int allowance) {
        this.myTeam = myTeam;
        this.allowance = allowance;
    }

    @GetMapping
    public ResponseEntity<MyTeamResponse> myTeam(@AuthenticationPrincipal Jwt jwt) {
        // A fresh mutable budget per request. Keeping one on this singleton would let one user's
        // page load spend another user's allowance.
        MyTeamService.Result result = myTeam.myTeam(TokenSubject.userId(jwt),
                new FplBudget(allowance));
        HttpStatus status = result instanceof MyTeamService.Result.Unavailable
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status)
                // The server-side FPL caches stay useful; this only stops a browser or shared
                // proxy retaining one authenticated account's squad response.
                .cacheControl(CacheControl.noStore())
                .body(MyTeamResponse.from(result));
    }
}
