package fi.karivanttinen.fplassistant.sync;

import java.util.Objects;
import java.util.regex.Pattern;

/** SQL identifiers for the inert owned-sync store. */
public record OwnedSyncTables(String run, String payload, String receipt,
                              String requestState, String requestAttempt, String fixtureSync,
                              String playerFixtureEvidence, String plannerSlot, String plannerRuntime,
                              String fixtureDependencyManifest) {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,39}");

    public OwnedSyncTables {
        run = valid(run);
        payload = valid(payload);
        receipt = valid(receipt);
        requestState = valid(requestState);
        requestAttempt = valid(requestAttempt);
        fixtureSync = valid(fixtureSync);
        playerFixtureEvidence = valid(playerFixtureEvidence);
        plannerSlot = valid(plannerSlot);
        plannerRuntime = valid(plannerRuntime);
        fixtureDependencyManifest = valid(fixtureDependencyManifest);
    }

    /** Compatibility constructor for callers that name all existing tables explicitly. */
    public OwnedSyncTables(String run, String payload, String receipt,
                           String requestState, String requestAttempt, String fixtureSync,
                           String playerFixtureEvidence, String plannerSlot, String plannerRuntime) {
        this(run, payload, receipt, requestState, requestAttempt, fixtureSync, playerFixtureEvidence,
                plannerSlot, plannerRuntime, dependencyManifestName(run));
    }

    /** Compatibility constructor for callers that name the fixture publication tables explicitly. */
    public OwnedSyncTables(String run, String payload, String receipt,
                           String requestState, String requestAttempt, String fixtureSync,
                           String playerFixtureEvidence) {
        this(run, payload, receipt, requestState, requestAttempt, fixtureSync, playerFixtureEvidence,
                run + "_planner_slot", run + "_planner_runtime", dependencyManifestName(run));
    }

    /** Compatibility constructor for callers that name the Task 2 request tables explicitly. */
    public OwnedSyncTables(String run, String payload, String receipt,
                           String requestState, String requestAttempt) {
        this(run, payload, receipt, requestState, requestAttempt, run + "_fixture", run + "_pfs_evidence",
                run + "_planner_slot", run + "_planner_runtime");
    }

    /** Compatibility constructor for Task 2a callers that only name the original tables. */
    public OwnedSyncTables(String run, String payload, String receipt) {
        this(run, payload, receipt, run + "_request_state", run + "_request_attempt",
                run + "_fixture", run + "_pfs_evidence", run + "_planner_slot", run + "_planner_runtime");
    }

    public static OwnedSyncTables defaults() {
        return new OwnedSyncTables("fpl_sync_run", "fpl_sync_payload", "fpl_sync_receipt",
                "fpl_sync_request_state", "fpl_sync_request_attempt", "fpl_fixture_sync",
                "fpl_player_fixture_evidence", "fpl_sync_planner_slot", "fpl_sync_planner_runtime",
                "fpl_fixture_dependency_manifest");
    }

    public static OwnedSyncTables tests() {
        return new OwnedSyncTables("fpl_sync_run_test", "fpl_sync_payload_test", "fpl_sync_receipt_test",
                "fpl_sync_request_state_test", "fpl_sync_request_attempt_test", "fpl_fixture_sync_test",
                "fpl_player_fixture_evidence_test", "fpl_sync_planner_slot_test",
                "fpl_sync_planner_runtime_test", "fpl_fixture_dependency_manifest_test");
    }

    public String historyFingerprint() {
        return valid(run + "_history_fingerprint");
    }

    public String playerFixtureCurrent() {
        if (run.equals("fpl_sync_run")) return "fpl_player_fixture_current";
        if (run.equals("fpl_sync_run_test")) return "fpl_player_fixture_current_test";
        return valid(run + "_pfs_current");
    }

    public String gameweekStatSync() {
        if (run.equals("fpl_sync_run")) return "fpl_gameweek_stat_sync";
        if (run.equals("fpl_sync_run_test")) return "fpl_gameweek_stat_sync_test";
        return valid(run + "_gameweek_stat_sync");
    }

    public String alert() {
        if (run.equals("fpl_sync_run")) return "fpl_sync_alert";
        if (run.equals("fpl_sync_run_test")) return "fpl_sync_alert_test";
        return valid(run + "_alert");
    }

    private static String dependencyManifestName(String run) {
        return valid(run + "_fixture_dep_manifest");
    }

    private static String valid(String identifier) {
        Objects.requireNonNull(identifier, "SQL identifier");
        if (!SQL_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        return identifier;
    }
}
