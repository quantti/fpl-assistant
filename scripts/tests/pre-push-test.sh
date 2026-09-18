#!/usr/bin/env bash
# Stub-only contract tests for the master guard in scripts/hooks/pre-push and for scripts/test-db.sh.
# These copy the production scripts into disposable repositories; they never call real Docker.
set -uo pipefail

SOURCE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

run_integration() {
    local container="fpl-kar36-reset-integration"
    local owned=false

    command -v docker >/dev/null 2>&1 || { echo "integration: docker not found" >&2; return 1; }
    docker info >/dev/null 2>&1 || { echo "integration: Docker daemon unavailable" >&2; return 1; }

    local inspection
    if inspection="$(docker container inspect "$container" 2>&1)"; then
        echo "integration: refusing existing fixture container $container" >&2
        return 1
    elif [[ "$inspection" != *"No such container: $container"* ]]; then
        echo "integration: could not inspect fixture container: $inspection" >&2
        return 1
    fi

    local port
    port="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"

    cleanup_integration() {
        if [[ "$owned" == true ]]; then
            docker rm -f "$container" >/dev/null 2>&1 || true
        fi
    }
    trap cleanup_integration RETURN

    owned=true
    TEST_DB_CONTAINER="$container" TEST_DB_PORT="$port" "$SOURCE_ROOT/scripts/test-db.sh" reset >/dev/null \
        || return 1
    local initial_id
    initial_id="$(docker container inspect --format '{{.Id}}' "$container")" || return 1
    docker exec "$container" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q \
        -c 'CREATE TABLE kar36_reset_sentinel (id integer)' >/dev/null || return 1

    TEST_DB_CONTAINER="$container" TEST_DB_PORT="$port" "$SOURCE_ROOT/scripts/test-db.sh" reset >/dev/null \
        || return 1
    local reset_id
    reset_id="$(docker container inspect --format '{{.Id}}' "$container")" || return 1
    [[ "$initial_id" != "$reset_id" ]] || { echo "integration: reset did not recreate the container" >&2; return 1; }

    assert_clean_database "$container" || return 1
    TEST_DB_CONTAINER="$container" TEST_DB_PORT="$port" "$SOURCE_ROOT/scripts/test-db.sh" reset >/dev/null \
        || return 1
    assert_clean_database "$container" || return 1
    echo "integration: fresh reset verified twice on $container port $port"
}

assert_clean_database() {
    local container="$1"
    local result
    result="$(docker exec "$container" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -tAc \
        "SELECT to_regclass('public.kar36_reset_sentinel') IS NULL
              AND to_regclass('public.season') IS NOT NULL
              AND EXISTS (SELECT FROM pg_extension WHERE extname = 'vector')
              AND NOT EXISTS (
                  SELECT FROM pg_tables
                  WHERE schemaname = 'public'
                    AND (tablename LIKE 'fpl\\_%' ESCAPE '\\' OR tablename = 'pl_match_result_test')
              )")"
    [[ "$result" == t ]] || { echo "integration: database was not in the clean starting state" >&2; return 1; }
}

if [[ "${1:-}" == --integration ]]; then
    [[ "$#" -eq 1 ]] || { echo "usage: $0 [--integration]" >&2; exit 2; }
    run_integration
    exit
elif [[ "$#" -ne 0 ]]; then
    echo "usage: $0 [--integration]" >&2
    exit 2
fi

TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "$TMP_ROOT"' EXIT

passes=0
failures=0
failure_names=()

pass() {
    printf 'ok %d - %s\n' "$((passes + failures + 1))" "$1"
    passes=$((passes + 1))
}

fail() {
    printf 'not ok %d - %s: %s\n' "$((passes + failures + 1))" "$1" "$2"
    failures=$((failures + 1))
    failure_names+=("$1 — $2")
}

new_fixture() {
    local name="$1"
    FIXTURE="$TMP_ROOT/$name"
    mkdir -p "$FIXTURE/scripts/hooks" \
        "$FIXTURE/backend/src/test/resources/db" "$FIXTURE/bin" "$FIXTURE/home"
    cp "$SOURCE_ROOT/scripts/hooks/pre-push" "$FIXTURE/scripts/hooks/pre-push"
    cp "$SOURCE_ROOT/scripts/test-db.sh" "$FIXTURE/scripts/test-db.sh"
    : > "$FIXTURE/backend/src/test/resources/db/shared-stats-schema.sql"
    LOG="$FIXTURE/calls.log"
    : > "$LOG"
    cat > "$FIXTURE/bin/git" <<'STUB'
#!/usr/bin/env bash
printf 'git %s\n' "$*" >> "$CALL_LOG"
if [[ "${1:-}" == rev-parse && "${2:-}" == --show-toplevel ]]; then
    printf '%s\n' "$FIXTURE_ROOT"
    exit 0
fi
exit 2
STUB

    cat > "$FIXTURE/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "$CALL_LOG"
case "${1:-}" in
    info)
        [[ "${DOCKER_FAIL_INFO:-0}" == 1 ]] && exit 1
        ;;
    context)
        if [[ "${2:-}" == inspect ]]; then
            [[ "${DOCKER_FAIL_CONTEXT:-0}" == 1 ]] && exit 1
            printf '%s\n' "${DOCKER_ENDPOINT:-unix:///var/run/docker.sock}"
        fi
        ;;
    container)
        if [[ "${2:-}" == inspect ]]; then
            if [[ "${DOCKER_FAIL_INSPECT:-0}" == 1 ]]; then
                printf 'daemon inspection failed\n' >&2
                exit 1
            fi
            if [[ ! -f "$FIXTURE_ROOT/container-absent" ]] \
                    && { [[ "${DOCKER_RUNNING:-0}" == 1 ]] || [[ "${DOCKER_STOPPED:-0}" == 1 ]] \
                         || [[ -f "$FIXTURE_ROOT/container-present" ]]; }; then
                if [[ "$*" == *"--format"* ]]; then
                    [[ "${DOCKER_STOPPED:-0}" == 1 ]] && printf 'false\n' || printf 'true\n'
                else
                    printf '{}\n'
                fi
                exit 0
            fi
            printf 'Error: No such container: fpl-test-db\n' >&2
            exit 1
        fi
        ;;
    rm)
        [[ "${DOCKER_FAIL_RM:-0}" == 1 ]] && exit 1
        if [[ "${DOCKER_RM_LEAVES_CONTAINER:-0}" != 1 ]]; then
            rm -f "$FIXTURE_ROOT/container-present"
            : > "$FIXTURE_ROOT/container-absent"
        fi
        ;;
    run)
        [[ "${DOCKER_FAIL_RUN:-0}" == 1 ]] && exit 1
        rm -f "$FIXTURE_ROOT/container-absent"
        : > "$FIXTURE_ROOT/container-present"
        printf 'fixture-container\n'
        ;;
    exec)
        if [[ "$*" == *"pg_isready"* ]]; then
            exit 0
        fi
        if [[ "$*" == *"SELECT to_regclass"* ]]; then
            [[ "${DOCKER_SCHEMA_PRESENT:-0}" == 1 ]] && printf 'season\n'
            exit 0
        fi
        [[ "${DOCKER_FAIL_SCHEMA:-0}" == 1 ]] && exit 1
        ;;
esac
exit 0
STUB

    chmod +x "$FIXTURE/bin/git" "$FIXTURE/bin/docker"
}

run_hook() {
    local remote_ref="${1:-refs/heads/feature}"
    local local_sha="${2:-1111111111111111111111111111111111111111}"
    shift 2 || true
    OUTPUT="$FIXTURE/output"
    env -i HOME="$FIXTURE/home" PATH="$FIXTURE/bin:/usr/bin:/bin" \
        FIXTURE_ROOT="$FIXTURE" CALL_LOG="$LOG" "$@" \
        bash "$FIXTURE/scripts/hooks/pre-push" >"$OUTPUT" 2>&1 <<EOF
refs/heads/feature $local_sha $remote_ref 0000000000000000000000000000000000000000
EOF
    STATUS=$?
}

# The hook is a guard and nothing else: no Docker, no Gradle, on any push.
no_docker_or_gradle() {
    ! grep -Eq '^docker |^gradle ' "$LOG"
}

check_feature_push_is_light() {
    local name="a feature-branch push passes without Docker or Gradle"
    new_fixture feature-push
    run_hook refs/heads/feature 1111111111111111111111111111111111111111
    if [[ "$STATUS" -eq 0 ]] && no_docker_or_gradle; then
        pass "$name"
    else
        fail "$name" "the hook failed or started lifecycle work"
    fi
}

run_reset() {
    OUTPUT="$FIXTURE/output"
    env -i HOME="$FIXTURE/home" PATH="$FIXTURE/bin:/usr/bin:/bin" \
        FIXTURE_ROOT="$FIXTURE" CALL_LOG="$LOG" "$@" \
        bash "$FIXTURE/scripts/test-db.sh" reset >"$OUTPUT" 2>&1
    STATUS=$?
}

check_reset_failure() {
    local id="$1" name="$2" flag="$3"
    new_fixture "$id"
    run_reset "$flag"=1
    if [[ "$STATUS" -ne 0 ]]; then
        pass "$name"
    else
        fail "$name" "reset reported success"
    fi
}

check_reset_port() {
    local name="TEST_DB_PORT selects the reset container's port"
    new_fixture reset-port
    run_reset TEST_DB_PORT=56543
    if [[ "$STATUS" -eq 0 ]] && grep -q 'docker run .* -p 56543:5432 ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "the port override did not reach docker run"
    fi
}

check_running_removal_failure() {
    local name="a failed removal cannot reuse a running container"
    new_fixture failed-removal
    run_reset DOCKER_RUNNING=1 DOCKER_SCHEMA_PRESENT=1 DOCKER_FAIL_RM=1
    if [[ "$STATUS" -ne 0 ]] && ! grep -q '^docker run ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "reset suppressed docker rm failure or created a replacement"
    fi
}

check_unremoved_container() {
    local name="reset verifies that a reportedly removed container is absent"
    new_fixture unremoved-container
    run_reset DOCKER_RUNNING=1 DOCKER_RM_LEAVES_CONTAINER=1
    if [[ "$STATUS" -ne 0 ]] && ! grep -q '^docker run ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "reset created a replacement without proving removal"
    fi
}

check_absent_container() {
    local name="an absent container is recreated without a removal attempt"
    new_fixture absent-container
    run_reset
    if [[ "$STATUS" -eq 0 ]] && grep -q '^docker run ' "$LOG" && ! grep -q '^docker rm ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "absence was treated as an inspection error or removal target"
    fi
}

check_inspection_failure() {
    local name="container inspection errors fail before removal or creation"
    new_fixture inspection-error
    run_reset DOCKER_FAIL_INSPECT=1
    if [[ "$STATUS" -ne 0 ]] && ! grep -Eq '^docker (rm|run) ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "inspection error was treated as container absence"
    fi
}

check_direct_schema_preflight() {
    local name="direct reset checks schema readability before container mutation"
    new_fixture reset-missing-schema
    rm "$FIXTURE/backend/src/test/resources/db/shared-stats-schema.sql"
    run_reset DOCKER_RUNNING=1
    if [[ "$STATUS" -ne 0 ]] && ! grep -Eq '^docker (container|rm|run|exec) ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "reset inspected or changed Docker before checking the schema"
    fi
}

check_deletion_gate() {
    local name="deletion-only pushes remain a no-op"
    new_fixture deletion
    run_hook refs/heads/feature 0000000000000000000000000000000000000000
    if [[ "$STATUS" -eq 0 ]] && no_docker_or_gradle; then pass "$name"; else fail "$name" "deletion performed work or failed"; fi
}

check_master_gate() {
    local name="noninteractive master pushes are refused"
    new_fixture master
    run_hook refs/heads/master 1111111111111111111111111111111111111111
    if [[ "$STATUS" -ne 0 ]] && no_docker_or_gradle \
        && grep -q 'Refusing: no terminal to confirm' "$OUTPUT"; then
        pass "$name"
    else
        fail "$name" "the master gate did not refuse"
    fi
}

check_integration_setup_failures() {
    local name="integration setup failures are returned and still cleaned up"
    local mode all_failed=true
    for mode in initial-id sentinel reset-id; do
        local root="$TMP_ROOT/integration-setup-$mode"
        mkdir -p "$root/scripts/tests" "$root/bin"
        cp "$SOURCE_ROOT/scripts/tests/pre-push-test.sh" "$root/scripts/tests/pre-push-test.sh"
        cat > "$root/scripts/test-db.sh" <<'STUB'
#!/usr/bin/env bash
count=0
[[ ! -f "$RESET_COUNT" ]] || count="$(< "$RESET_COUNT")"
printf '%d\n' "$((count + 1))" > "$RESET_COUNT"
STUB
        local log="$root/docker.log"
        cat > "$root/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "$DOCKER_LOG"
case "${1:-}" in
    info) exit 0 ;;
    container)
        if [[ "${2:-}" == inspect && "$*" == *"{{.Id}}"* ]]; then
            count="$(< "$RESET_COUNT")"
            [[ "$FAIL_MODE" != initial-id || "$count" -ne 1 ]] || exit 1
            [[ "$FAIL_MODE" != reset-id || "$count" -ne 2 ]] || exit 1
            printf 'id-%s\n' "$count"
            exit 0
        fi
        printf 'Error: No such container: fpl-kar36-reset-integration\n' >&2
        exit 1
        ;;
    exec)
        if [[ "$*" == *"CREATE TABLE kar36_reset_sentinel"* ]]; then
            [[ "$FAIL_MODE" != sentinel ]]
            exit
        fi
        printf 't\n'
        ;;
esac
exit 0
STUB
        chmod +x "$root/bin/docker" "$root/scripts/test-db.sh"

        local output="$root/output" status
        env -i PATH="$root/bin:/usr/bin:/bin" DOCKER_LOG="$log" RESET_COUNT="$root/reset-count" \
            FAIL_MODE="$mode" bash "$root/scripts/tests/pre-push-test.sh" --integration >"$output" 2>&1
        status=$?
        if [[ "$status" -eq 0 ]] \
            || ! grep -q '^docker rm -f fpl-kar36-reset-integration$' "$log" \
            || grep -q '^integration: fresh reset verified twice' "$output"; then
            all_failed=false
        fi
    done
    if [[ "$all_failed" == true ]]; then
        pass "$name"
    else
        fail "$name" "an ID inspection or sentinel failure was suppressed or skipped cleanup"
    fi
}

check_integration_reset_failure() {
    local name="integration reset failure is returned and still cleaned up"
    local root="$TMP_ROOT/integration-reset-failure"
    mkdir -p "$root/scripts/tests" "$root/bin"
    cp "$SOURCE_ROOT/scripts/tests/pre-push-test.sh" "$root/scripts/tests/pre-push-test.sh"
    cat > "$root/scripts/test-db.sh" <<'STUB'
#!/usr/bin/env bash
count=0
[[ ! -f "$RESET_COUNT" ]] || count="$(< "$RESET_COUNT")"
printf '%d\n' "$((count + 1))" > "$RESET_COUNT"
(( count > 0 ))
STUB
    local log="$root/docker.log"
    cat > "$root/bin/docker" <<'STUB'
#!/usr/bin/env bash
printf 'docker %s\n' "$*" >> "$DOCKER_LOG"
case "${1:-}" in
    info) exit 0 ;;
    container)
        if [[ "${2:-}" == inspect && "$*" == *"{{.Id}}"* ]]; then
            [[ "$(< "$RESET_COUNT")" -gt 1 ]] && printf 'new-id\n' || printf 'stale-id\n'
            exit 0
        fi
        printf 'Error: No such container: fpl-kar36-reset-integration\n' >&2
        exit 1
        ;;
    exec) [[ "$*" != *"kar36_reset_sentinel"* ]] || printf 't\n' ;;
esac
exit 0
STUB
    chmod +x "$root/bin/docker" "$root/scripts/test-db.sh"

    local output="$root/output" status
    env -i PATH="$root/bin:/usr/bin:/bin" DOCKER_LOG="$log" RESET_COUNT="$root/reset-count" \
        bash "$root/scripts/tests/pre-push-test.sh" --integration >"$output" 2>&1
    status=$?
    if [[ "$status" -ne 0 ]] \
        && grep -q '^docker rm -f fpl-kar36-reset-integration$' "$log" \
        && ! grep -q '^integration: fresh reset verified twice' "$output"; then
        pass "$name"
    else
        fail "$name" "failed reset was reported as successful or skipped cleanup"
    fi
}

check_feature_push_is_light
check_reset_failure reset-run-failure "container creation failure fails the reset" DOCKER_FAIL_RUN
check_reset_failure schema-load-failure "schema-load failure fails the reset" DOCKER_FAIL_SCHEMA
check_reset_port
check_running_removal_failure
check_unremoved_container
check_absent_container
check_inspection_failure
check_direct_schema_preflight
check_deletion_gate
check_master_gate
check_integration_setup_failures
check_integration_reset_failure

printf '\n%d passed, %d failed\n' "$passes" "$failures"
if (( failures > 0 )); then
    printf 'Failed contracts:\n'
    printf '  - %s\n' "${failure_names[@]}"
    exit 1
fi
