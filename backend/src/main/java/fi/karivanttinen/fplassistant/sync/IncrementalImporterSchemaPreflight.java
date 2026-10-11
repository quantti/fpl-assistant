package fi.karivanttinen.fplassistant.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/** Read-only startup gate for the already-installed schema required by the incremental importer. */
final class IncrementalImporterSchemaPreflight {

    private final JdbcTemplate jdbc;
    private final OwnedSyncTables tables;

    IncrementalImporterSchemaPreflight(JdbcTemplate jdbc, OwnedSyncTables tables) {
        this.jdbc = jdbc;
        this.tables = tables;
    }

    void requireReady() {
        try {
            List<String> missing = missingColumns();
            missing.addAll(missingProofConstraints());
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Incremental importer schema is not ready; missing "
                        + String.join(", ", missing)
                        + ". Install the owned-sync schema migration before enabling the importer.");
            }
        } catch (DataAccessException unavailable) {
            throw new IllegalStateException("Incremental importer could not verify the installed owned-sync "
                    + "schema from PostgreSQL catalogs; install the schema migration and grant catalog read access.",
                    unavailable);
        }
    }

    private List<String> missingColumns() {
        Map<String, Set<String>> required = requiredColumns();
        String placeholders = String.join(",", java.util.Collections.nCopies(required.size(), "?"));
        List<Object> parameters = new ArrayList<>(required.keySet());
        Map<String, Set<String>> present = new LinkedHashMap<>();
        jdbc.query("SELECT table_name,column_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name IN (" + placeholders + ")",
                (RowCallbackHandler) rs -> present.computeIfAbsent(rs.getString("table_name"),
                                ignored -> new LinkedHashSet<>()).add(rs.getString("column_name")),
                parameters.toArray());
        List<String> missing = new ArrayList<>();
        required.forEach((table, columns) -> columns.stream().filter(column ->
                !present.getOrDefault(table, Set.of()).contains(column))
                .map(column -> table + "." + column).forEach(missing::add));
        return missing;
    }

    private List<String> missingProofConstraints() {
        List<RequiredConstraint> required = List.of(
                new RequiredConstraint(tables.run(), "ck_" + tables.run() + "_state", "SUPERSEDED"),
                new RequiredConstraint(tables.playerFixtureEvidence(),
                        "ck_" + tables.playerFixtureEvidence() + "_components", "component_presence"),
                new RequiredConstraint(tables.playerFixtureCurrent(),
                        "ck_" + tables.playerFixtureCurrent() + "_pointer", "row_fingerprint"),
                new RequiredConstraint(tables.gameweekStatSync(),
                        "ck_" + tables.gameweekStatSync() + "_checkpoint", "final_attempt_completed_player_ids"),
                new RequiredConstraint(tables.gameweekStatSync(),
                        "ck_" + tables.gameweekStatSync() + "_receipt", "finalized_page_evidence"));
        List<String> missing = new ArrayList<>();
        for (RequiredConstraint constraint : required) {
            List<String> definitions = jdbc.query("""
                    SELECT pg_get_constraintdef(oid) FROM pg_constraint
                    WHERE conrelid=to_regclass(?) AND conname=? AND contype='c' AND convalidated
                    """, (rs, row) -> rs.getString(1), "public." + constraint.table(), constraint.name());
            if (definitions.size() != 1 || !definitions.getFirst().contains(constraint.requiredDefinitionPart())) {
                missing.add(constraint.table() + "." + constraint.name());
            }
        }
        if (!payloadProofGuardReady()) missing.add(tables.payload() + ".trg_owned_payload_source_guard");
        return missing;
    }

    private boolean payloadProofGuardReady() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_trigger t
                    WHERE t.tgrelid=to_regclass(?) AND t.tgname='trg_owned_payload_source_guard'
                      AND NOT t.tgisinternal AND t.tgenabled IN ('O','A') AND (t.tgtype & 23)=23
                      AND position('source_payload_sha256' in pg_get_functiondef(t.tgfoid))>0
                      AND position('body_pruned_at' in pg_get_functiondef(t.tgfoid))>0
                      AND position('sha256' in pg_get_functiondef(t.tgfoid))>0
                )
                """, Boolean.class, "public." + tables.payload()));
    }

    private Map<String, Set<String>> requiredColumns() {
        Map<String, Set<String>> required = new LinkedHashMap<>();
        add(required, tables.run(), "generation kind producer_key season coalescing_key publication_key priority due_at "
                + "requested_source_after runtime_mode runtime_epoch state attempt_count consecutive_fetch_failures "
                + "last_attempt_at lease_owner lease_token lease_fence lease_expires_at source_read_started_at "
                + "source_read_finished_at last_error failed_at completed_at created_at updated_at");
        add(required, tables.payload(), "id run_generation endpoint_kind event_id player_id read_started_at "
                + "read_finished_at validation_state validation_error source_published source_payload_sha256 "
                + "body_pruned_at body staged_at");
        add(required, tables.receipt(), "season publication_key latest_generation source_read_started_at "
                + "source_read_finished_at published_at source_bootstrap_payload_id source_fixtures_payload_id "
                + "source_bootstrap_read_started_at source_bootstrap_read_finished_at "
                + "source_fixtures_read_started_at source_fixtures_read_finished_at source_bootstrap_sha256 "
                + "source_fixtures_sha256 source_pair_fingerprint source_catalogue");
        add(required, tables.requestState(), "singleton last_admitted_at cooldown_until");
        add(required, tables.requestAttempt(), "permit_id admitted_at permit_expires_at completed_at hold_until_expiry");
        add(required, tables.fixtureSync(), "season_id season_code fixture_id state observation observation_generation "
                + "current_verified_generation last_verified_generation verified_header verified_stats_fingerprint "
                + "first_finished_observed_at finished_parent_generation finished_parent_publication_generation "
                + "finished_parent_bootstrap_payload_id finished_parent_fixtures_payload_id "
                + "finished_parent_source_fingerprint finished_parent_fixture_fingerprint finished_parent_finished "
                + "data_checked_transition_at last_data_checked expected_participants received_participants "
                + "missing_player_ids validation_failures source_read_started_at source_read_finished_at "
                + "next_due_at attempt_count last_error alert_state updated_at "
                + "recovery_day recovery_started_at recovery_until due_reason transport_retry_at dependency_retry_at");
        add(required, tables.playerFixtureEvidence(), "season_id season_code player_id fixture_id generation "
                + "component_presence source_read_started_at source_read_finished_at");
        add(required, tables.historyFingerprint(), "season_id season_code player_id fixture_id fingerprint "
                + "source_read_finished_at updated_at");
        add(required, tables.playerFixtureCurrent(), "season_id season_code fixture_id player_id row_state "
                + "source_run_generation evidence_generation row_fingerprint physical_fingerprint "
                + "source_read_started_at source_read_finished_at updated_at");
        add(required, tables.gameweekStatSync(), "season_id season_code gameweek state event_finished data_checked "
                + "fixture_manifest_complete all_assigned_fixtures_finished source_signature first_checked_at "
                + "current_invalidation_revision current_invalidation_floor final_attempt_source_signature "
                + "final_attempt_source_floor final_attempt_invalidation_revision "
                + "final_attempt_population_fingerprint final_attempt_population_manifest "
                + "final_attempt_source_read_started_at final_attempt_initial_source_read_finished_at "
                + "final_attempt_source_read_finished_at final_attempt_required_player_ids "
                + "final_attempt_completed_player_ids final_attempt_outstanding_player_ids "
                + "final_attempt_page_read_started_at final_attempt_page_read_finished_at "
                + "finalized_source_signature finalized_population_fingerprint finalized_row_revision_fingerprint "
                + "finalized_source_floor finalized_validation_read_started_at finalized_validation_read_finished_at "
                + "finalized_source_read_started_at finalized_source_read_finished_at finalized_invalidation_revision "
                + "finalized_run_generation finalized_page_evidence finalized_at "
                + "finalized_mandatory_manifest_complete finalized_optional_component_coverage "
                + "legacy_finality_evidence updated_at");
        add(required, tables.plannerSlot(), "slot_id season kind due_at fixture_ids player_ids reason "
                + "estimated_requests enqueued_at");
        add(required, tables.plannerRuntime(), "instance_id started_at heartbeat_at last_error last_error_at "
                + "runtime_mode runtime_epoch activation_boundary");
        add(required, tables.fixtureDependencyManifest(), "run_generation bootstrap_payload_id fixtures_payload_id "
                + "live_payload_id event_id required_player_ids source_floor");
        add(required, tables.alert(), "id incident_key occurrence event_type category severity season fixture_id "
                + "payload delivery_state delivery_attempts next_delivery_at delivery_token delivery_lease_until "
                + "last_delivery_error created_at delivered_at");
        return required;
    }

    private static void add(Map<String, Set<String>> required, String table, String columns) {
        required.put(table, Set.of(columns.split(" ")));
    }

    private record RequiredConstraint(String table, String name, String requiredDefinitionPart) { }
}
