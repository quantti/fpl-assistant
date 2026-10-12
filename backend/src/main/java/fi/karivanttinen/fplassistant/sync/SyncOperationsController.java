package fi.karivanttinen.fplassistant.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Dedicated-token operations surface. These parameters are never exposed as chat tools. */
@RestController
@ConditionalOnExpression("${fpl.owned-sync.ops.enabled:false} || ${fpl.owned-sync.external-health.enabled:false}")
public final class SyncOperationsController {

    public static final String OPERATOR_HEADER = "X-FPL-Operator-Token";
    public static final String MONITOR_HEADER = "X-FPL-Monitor-Token";

    private final SyncOperations operations;
    private final boolean operationsEnabled;
    private final String operatorToken;
    private final boolean monitorEnabled;
    private final String monitorToken;

    public SyncOperationsController(SyncOperations operations,
                                    @Value("${fpl.owned-sync.ops.enabled:false}") boolean operationsEnabled,
                                    @Value("${fpl.owned-sync.ops.token:}") String operatorToken,
                                    @Value("${fpl.owned-sync.external-health.enabled:false}") boolean monitorEnabled,
                                    @Value("${fpl.owned-sync.external-health.token:}") String monitorToken) {
        this.operations = operations;
        this.operationsEnabled = operationsEnabled;
        this.operatorToken = operatorToken == null ? "" : operatorToken;
        this.monitorEnabled = monitorEnabled;
        this.monitorToken = monitorToken == null ? "" : monitorToken;
    }

    @GetMapping("/ops/sync/status")
    public SyncOperations.Status status(@RequestHeader(value = OPERATOR_HEADER, required = false) String token) {
        requireEnabled(operationsEnabled);
        requireToken(operatorToken, token);
        return operations.status();
    }

    @PostMapping("/ops/sync/retry")
    public Map<String, Long> retry(@RequestHeader(value = OPERATOR_HEADER, required = false) String token,
                                   @RequestBody RetryRequest request) {
        requireEnabled(operationsEnabled);
        requireToken(operatorToken, token);
        try {
            return Map.of("generation", operations.retry(request.failedGeneration(),
                    request.expectedRuntimeEpoch()));
        } catch (SyncOperations.StaleOperatorRequestException | DataIntegrityViolationException stale) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Retry conflicts with current activation or work");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @PostMapping("/ops/sync/importer/handoff")
    public Map<String, Long> handoffImporter(
            @RequestHeader(value = OPERATOR_HEADER, required = false) String token,
            @RequestBody ImporterHandoffRequest request) {
        requireEnabled(operationsEnabled);
        requireToken(operatorToken, token);
        try {
            return Map.of("runtimeEpoch", operations.handoffImporter(
                    request.playingSeason(), request.expectedOffEpoch()));
        } catch (SyncOperations.StaleOperatorRequestException | DataIntegrityViolationException stale) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Importer handoff is fenced or unavailable");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @PostMapping("/ops/sync/importer/retire")
    public Map<String, Long> retireImporter(
            @RequestHeader(value = OPERATOR_HEADER, required = false) String token,
            @RequestBody ImporterRetireRequest request) {
        requireEnabled(operationsEnabled);
        requireToken(operatorToken, token);
        try {
            return Map.of("offEpoch", operations.retireImporter(
                    request.playingSeason(), request.expectedImporterEpoch()));
        } catch (SyncOperations.StaleOperatorRequestException | DataIntegrityViolationException stale) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Importer rollback is fenced or unavailable");
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @PostMapping("/ops/sync/alerts/retry")
    public Map<String, Long> redriveAlert(
            @RequestHeader(value = OPERATOR_HEADER, required = false) String token,
            @RequestBody AlertRetryRequest request) {
        requireEnabled(operationsEnabled);
        requireToken(operatorToken, token);
        try {
            return Map.of("alertId", operations.redriveAlert(request.failedAlertId()));
        } catch (SyncOperations.StaleOperatorRequestException stale) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, stale.getMessage());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    @GetMapping("/ops/sync/health")
    public ResponseEntity<Map<String, String>> health(
            @RequestHeader(value = MONITOR_HEADER, required = false) String token) {
        requireEnabled(monitorEnabled);
        requireToken(monitorToken, token);
        return operations.healthy()
                ? ResponseEntity.ok(Map.of("status", "UP"))
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
    }

    private static void requireEnabled(boolean enabled) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static void requireToken(String expected, String supplied) {
        if (expected.isBlank() || supplied == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    public record RetryRequest(long failedGeneration, long expectedRuntimeEpoch) { }
    public record ImporterHandoffRequest(String playingSeason, long expectedOffEpoch) { }
    public record ImporterRetireRequest(String playingSeason, long expectedImporterEpoch) { }
    public record AlertRetryRequest(long failedAlertId) { }
}
