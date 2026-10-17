package fi.karivanttinen.fplassistant.shortlist;

import java.util.List;
import java.util.UUID;

import fi.karivanttinen.fplassistant.config.TokenSubject;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Desired-state API for the signed-in user's shortlist. */
@RestController
@RequestMapping("/api/shortlist")
public class ShortlistController {

    public record AddRequest(UUID catalogueId, String season, Integer elementId) {
    }

    public record RemoveRequest(String season, Integer elementId) {
    }

    private final ShortlistStore shortlist;
    private final ShortlistComparison comparison;

    public ShortlistController(ShortlistStore shortlist, ShortlistComparison comparison) {
        this.shortlist = shortlist;
        this.comparison = comparison;
    }

    @GetMapping("/comparison")
    public ResponseEntity<ShortlistComparison.Response> comparison(@AuthenticationPrincipal Jwt jwt) {
        UUID owner = TokenSubject.userId(jwt);
        try {
            return noStore(comparison.read(owner));
        } catch (org.springframework.dao.DataAccessException
                 | org.springframework.transaction.CannotCreateTransactionException failure) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore()).build();
        }
    }

    @GetMapping
    public ResponseEntity<List<ShortlistStore.Player>> list(@AuthenticationPrincipal Jwt jwt) {
        return noStore(shortlist.list(TokenSubject.userId(jwt)));
    }

    @PutMapping
    public ResponseEntity<ShortlistStore.Player> put(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestBody AddRequest request) {
        validate(request.season(), request.elementId());
        if (request.catalogueId() == null) {
            throw badRequest("catalogueId is required.");
        }
        ShortlistStore.Player player = shortlist.put(TokenSubject.userId(jwt), request.catalogueId(),
                        request.season(), request.elementId())
                .orElseThrow(() -> badRequest(
                        "The player does not belong to that stored catalogue and season."));
        return noStore(player);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt,
                                       @RequestBody RemoveRequest request) {
        validate(request.season(), request.elementId());
        shortlist.delete(TokenSubject.userId(jwt), request.season(), request.elementId());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static void validate(String season, Integer elementId) {
        if (season == null || season.isBlank() || season.length() > 10) {
            throw badRequest("season is required and must be at most 10 characters.");
        }
        if (elementId == null || elementId <= 0) {
            throw badRequest("elementId must be a positive number.");
        }
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
