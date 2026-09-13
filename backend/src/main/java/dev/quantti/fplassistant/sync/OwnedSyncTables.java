package dev.quantti.fplassistant.sync;

import java.util.Objects;
import java.util.regex.Pattern;

/** SQL identifiers for the inert owned-sync store. */
public record OwnedSyncTables(String run, String payload, String receipt,
                              String requestState, String requestAttempt) {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    public OwnedSyncTables {
        run = valid(run);
        payload = valid(payload);
        receipt = valid(receipt);
        requestState = valid(requestState);
        requestAttempt = valid(requestAttempt);
    }

    /** Compatibility constructor for Task 2a callers that only name the original tables. */
    public OwnedSyncTables(String run, String payload, String receipt) {
        this(run, payload, receipt, run + "_request_state", run + "_request_attempt");
    }

    public static OwnedSyncTables defaults() {
        return new OwnedSyncTables("fpl_sync_run", "fpl_sync_payload", "fpl_sync_receipt",
                "fpl_sync_request_state", "fpl_sync_request_attempt");
    }

    public static OwnedSyncTables tests() {
        return new OwnedSyncTables("fpl_sync_run_test", "fpl_sync_payload_test", "fpl_sync_receipt_test",
                "fpl_sync_request_state_test", "fpl_sync_request_attempt_test");
    }

    private static String valid(String identifier) {
        Objects.requireNonNull(identifier, "SQL identifier");
        if (!SQL_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return identifier;
    }
}
