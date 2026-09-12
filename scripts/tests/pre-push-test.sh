#!/usr/bin/env bash
# Stub-only contract tests for scripts/hooks/pre-push and scripts/test-db.sh.
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
    initial_id="$(docker container inspect --format '{{.Id}}' "$container")"
    docker exec "$container" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q \
        -c 'CREATE TABLE kar36_reset_sentinel (id integer)' >/dev/null

    TEST_DB_CONTAINER="$container" TEST_DB_PORT="$port" "$SOURCE_ROOT/scripts/test-db.sh" reset >/dev/null \
        || return 1
    local reset_id
    reset_id="$(docker container inspect --format '{{.Id}}' "$container")"
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
    mkdir -p "$FIXTURE/scripts/hooks" "$FIXTURE/scripts/tests" \
        "$FIXTURE/backend/src/test/resources/db" "$FIXTURE/bin" "$FIXTURE/home"
    cp "$SOURCE_ROOT/scripts/hooks/pre-push" "$FIXTURE/scripts/hooks/pre-push"
    cp "$SOURCE_ROOT/scripts/test-db.sh" "$FIXTURE/scripts/test-db.sh"
    : > "$FIXTURE/backend/src/test/resources/db/shared-stats-schema.sql"
    LOG="$FIXTURE/calls.log"
    : > "$LOG"
    mkdir -p "$FIXTURE/home/.sdkman/bin"
    cat > "$FIXTURE/home/.sdkman/bin/sdkman-init.sh" <<'STUB'
printf 'sdkman init\n' >> "$CALL_LOG"
STUB
    cat > "$FIXTURE/scripts/tests/pre-push-test.sh" <<'STUB'
#!/usr/bin/env bash
# The fixture hook must exercise the production call site without recursively starting this runner.
printf 'shell regressions\n' >> "$CALL_LOG"
exit "${SHELL_TEST_STATUS:-0}"
STUB

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

    cat > "$FIXTURE/backend/gradlew" <<'STUB'
#!/usr/bin/env bash
printf 'gradle %s DATABASE_URL=%s DB_USER=%s DB_PASSWORD=%s TEST_DB_PORT=%s\n' \
    "$*" "${DATABASE_URL:-}" "${DB_USER:-}" "${DB_PASSWORD:-}" "${TEST_DB_PORT:-}" >> "$CALL_LOG"
exit "${GRADLE_STATUS:-0}"
STUB
    chmod +x "$FIXTURE/bin/git" "$FIXTURE/bin/docker" "$FIXTURE/backend/gradlew" \
        "$FIXTURE/scripts/tests/pre-push-test.sh"
}

write_env() {
    local url="${1:-jdbc:postgresql://localhost:55432/postgres}"
    local user="${2:-postgres}"
    local password="${3:-postgres}"
    local extra="${4:-}"
    cat > "$FIXTURE/backend/.env.test" <<EOF
DATABASE_URL=$url
DB_USER=$user
DB_PASSWORD=$password
SPRING_AI_PROVIDER=anthropic
ANTHROPIC_API_KEY=dummy
OPENAI_API_KEY=dummy
$extra
EOF
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

no_mutation_or_gradle() {
    ! grep -Eq '^docker (rm|run|exec) |^gradle ' "$LOG"
}

line_of() {
    grep -n -m1 "$1" "$LOG" | cut -d: -f1
}

check_content_reset() {
    local name="content pushes reset before cleanTest test"
    new_fixture content-reset
    write_env
    run_hook refs/heads/feature 1111111111111111111111111111111111111111 \
        DOCKER_RUNNING=1 DOCKER_SCHEMA_PRESENT=1
    local rm_line gradle_line
    rm_line="$(line_of '^docker rm -f fpl-test-db$')"
    gradle_line="$(line_of '^gradle --console=plain --quiet cleanTest test ' )"
    if [[ "$STATUS" -eq 0 && -n "$rm_line" && -n "$gradle_line" && "$rm_line" -lt "$gradle_line" ]]; then
        pass "$name"
    else
        fail "$name" "hook reused up; no reset removal occurred before Gradle"
    fi
}

check_preflight_rejection() {
    local id="$1" name="$2"; shift 2
    new_fixture "$id"
    write_env "$@"
    run_hook refs/heads/feature 1111111111111111111111111111111111111111
    if [[ "$STATUS" -ne 0 ]] && no_mutation_or_gradle; then
        pass "$name"
    else
        fail "$name" "invalid destination reached Docker mutation or Gradle"
    fi
}

check_missing_config() {
    local name="missing configuration fails before mutation"
    new_fixture missing-config
    run_hook refs/heads/feature 1111111111111111111111111111111111111111
    if [[ "$STATUS" -ne 0 ]] && no_mutation_or_gradle; then pass "$name"; else fail "$name" "missing file was not an early failure"; fi
}

check_missing_variables() {
    local name="missing database variables fail before mutation"
    local variable rejected_all=true
    for variable in DATABASE_URL DB_USER DB_PASSWORD; do
        new_fixture "missing-$variable"
        write_env
        grep -v "^${variable}=" "$FIXTURE/backend/.env.test" > "$FIXTURE/backend/.env.test.tmp"
        mv "$FIXTURE/backend/.env.test.tmp" "$FIXTURE/backend/.env.test"
        run_hook refs/heads/feature 1111111111111111111111111111111111111111
        if [[ "$STATUS" -eq 0 ]] || ! no_mutation_or_gradle; then
            rejected_all=false
        fi
    done
    if [[ "$rejected_all" == true ]]; then
        pass "$name"
    else
        fail "$name" "at least one required database variable was inherited or ignored"
    fi
}

check_conflicting_override() {
    local name="competing Spring datasource overrides fail before mutation"
    local override rejected_all=true
    for override in \
        SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/postgres \
        SPRING_DATASOURCE_USERNAME=postgres \
        SPRING_DATASOURCE_PASSWORD=postgres; do
        new_fixture "datasource-${override%%=*}"
        write_env
        run_hook refs/heads/feature 1111111111111111111111111111111111111111 "$override"
        if [[ "$STATUS" -eq 0 ]] || ! no_mutation_or_gradle; then
            rejected_all=false
        fi
    done
    if [[ "$rejected_all" == true ]]; then
        pass "$name"
    else
        fail "$name" "at least one SPRING_DATASOURCE_* override was accepted"
    fi
}

check_port_override() {
    local name="matching TEST_DB_PORT reaches reset and Gradle"
    new_fixture matching-port
    write_env jdbc:postgresql://localhost:56543/postgres postgres postgres TEST_DB_PORT=56543
    run_hook refs/heads/feature 1111111111111111111111111111111111111111
    if [[ "$STATUS" -eq 0 ]] \
        && grep -q '^docker container inspect .* fpl-test-db$' "$LOG" \
        && grep -q 'docker run .* -p 56543:5432 ' "$LOG" \
        && grep -q '^gradle .*DATABASE_URL=jdbc:postgresql://localhost:56543/postgres .*TEST_DB_PORT=56543$' "$LOG"; then
        pass "$name"
    else
        fail "$name" "env-file port did not select the reset container before Gradle"
    fi
}

check_failure_blocks_gradle() {
    local id="$1" name="$2" flag="$3"
    new_fixture "$id"
    write_env
    run_hook refs/heads/feature 1111111111111111111111111111111111111111 "$flag"=1
    if [[ "$STATUS" -ne 0 ]] && ! grep -q '^gradle ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "lifecycle failure did not stop the hook before Gradle"
    fi
}

check_schema_preflight() {
    local name="unreadable schema fails before mutation"
    new_fixture missing-schema
    write_env
    rm "$FIXTURE/backend/src/test/resources/db/shared-stats-schema.sql"
    run_hook refs/heads/feature 1111111111111111111111111111111111111111
    if [[ "$STATUS" -ne 0 ]] && no_mutation_or_gradle; then pass "$name"; else fail "$name" "missing schema reached lifecycle work"; fi
}

check_docker_preflight() {
    local id="$1" name="$2"
    shift 2
    new_fixture "$id"
    write_env
    run_hook refs/heads/feature 1111111111111111111111111111111111111111 "$@"
    if [[ "$STATUS" -ne 0 ]] && no_mutation_or_gradle; then pass "$name"; else fail "$name" "unsafe Docker destination reached lifecycle work"; fi
}

check_context_overrides_host() {
    local name="explicit remote Docker context overrides local DOCKER_HOST"
    new_fixture context-overrides-local-host
    write_env
    run_hook refs/heads/feature 1111111111111111111111111111111111111111 \
        DOCKER_CONTEXT=remote-fixture DOCKER_HOST=unix:///var/run/docker.sock \
        DOCKER_ENDPOINT=tcp://docker.example.test:2376
    if [[ "$STATUS" -ne 0 ]] && no_mutation_or_gradle \
        && grep -q '^docker context inspect remote-fixture ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "local DOCKER_HOST hid the selected remote context"
    fi
}

check_local_context_overrides_remote_host() {
    local name="explicit local Docker context overrides remote DOCKER_HOST"
    new_fixture local-context-overrides-remote-host
    write_env
    run_hook refs/heads/feature 1111111111111111111111111111111111111111 \
        DOCKER_CONTEXT=local-fixture DOCKER_HOST=ssh://docker.example.test \
        DOCKER_ENDPOINT=unix:///var/run/docker.sock
    if [[ "$STATUS" -eq 0 ]] && grep -q '^docker context inspect local-fixture ' "$LOG" \
        && grep -q '^gradle ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "remote DOCKER_HOST hid the selected local context"
    fi
}

check_regressions_before_reset() {
    local name="shell regressions run before reset and can block it"
    new_fixture shell-regression-order
    write_env
    run_hook refs/heads/feature 1111111111111111111111111111111111111111 SHELL_TEST_STATUS=1
    if [[ "$STATUS" -ne 0 ]] && grep -q '^shell regressions$' "$LOG" \
        && ! grep -Eq '^docker (rm|run|exec) |^gradle ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "regression failure did not stop reset"
    fi
}

check_hook_removal_failure() {
    local name="container removal failure blocks Gradle"
    new_fixture hook-failed-removal
    write_env
    run_hook refs/heads/feature 1111111111111111111111111111111111111111 \
        DOCKER_RUNNING=1 DOCKER_SCHEMA_PRESENT=1 DOCKER_FAIL_RM=1
    if [[ "$STATUS" -ne 0 ]] && ! grep -q '^gradle ' "$LOG"; then
        pass "$name"
    else
        fail "$name" "hook reused the running container and executed Gradle"
    fi
}

run_reset() {
    OUTPUT="$FIXTURE/output"
    env -i HOME="$FIXTURE/home" PATH="$FIXTURE/bin:/usr/bin:/bin" \
        FIXTURE_ROOT="$FIXTURE" CALL_LOG="$LOG" "$@" \
        bash "$FIXTURE/scripts/test-db.sh" reset >"$OUTPUT" 2>&1
    STATUS=$?
}

check_running_removal_failure() {
    local name="a failed removal cannot reuse a running container"
    new_fixture failed-removal
    write_env
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
    if [[ "$STATUS" -eq 0 ]] && no_mutation_or_gradle; then pass "$name"; else fail "$name" "deletion performed work or failed"; fi
}

check_master_gate() {
    local name="noninteractive master pushes are refused before reset"
    new_fixture master
    write_env
    run_hook refs/heads/master 1111111111111111111111111111111111111111
    if [[ "$STATUS" -ne 0 ]] && no_mutation_or_gradle \
        && grep -q 'Refusing: no terminal to confirm' "$OUTPUT"; then
        pass "$name"
    else
        fail "$name" "real master gate did not refuse before lifecycle work"
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

check_content_reset
check_missing_config
check_missing_variables
check_preflight_rejection nonlocal-host "nonlocal database host fails before mutation" \
    jdbc:postgresql://db.example.test:55432/postgres postgres postgres
check_preflight_rejection wrong-port "TEST_DB_PORT mismatch fails before mutation" \
    jdbc:postgresql://localhost:56543/postgres postgres postgres TEST_DB_PORT=55432
check_preflight_rejection wrong-database "wrong database name fails before mutation" \
    jdbc:postgresql://localhost:55432/other postgres postgres
check_preflight_rejection wrong-user "wrong database user fails before mutation" \
    jdbc:postgresql://localhost:55432/postgres other postgres
check_preflight_rejection wrong-password "wrong database password fails before mutation" \
    jdbc:postgresql://localhost:55432/postgres postgres other
check_preflight_rejection jdbc-parameters "JDBC parameters fail before mutation" \
    'jdbc:postgresql://localhost:55432/postgres?ssl=true' postgres postgres
check_preflight_rejection invalid-port "invalid TEST_DB_PORT fails before mutation" \
    jdbc:postgresql://localhost:not-a-port/postgres postgres postgres TEST_DB_PORT=not-a-port
check_conflicting_override
check_schema_preflight
check_docker_preflight docker-unavailable "unavailable Docker daemon fails before mutation" DOCKER_FAIL_INFO=1
check_docker_preflight docker-context-unreadable "uninspectable Docker context fails before mutation" DOCKER_FAIL_CONTEXT=1
check_docker_preflight selected-context-unreadable "uninspectable selected Docker context fails before mutation" \
    DOCKER_CONTEXT=missing-fixture DOCKER_HOST=unix:///var/run/docker.sock DOCKER_FAIL_CONTEXT=1
check_docker_preflight remote-context "remote Docker context fails before mutation" DOCKER_ENDPOINT=tcp://docker.example.test:2376
check_docker_preflight remote-docker-host "remote DOCKER_HOST fails before mutation" DOCKER_HOST=ssh://docker.example.test
check_context_overrides_host
check_local_context_overrides_remote_host
check_regressions_before_reset
check_port_override
check_failure_blocks_gradle reset-run-failure "container creation failure blocks Gradle" DOCKER_FAIL_RUN
check_failure_blocks_gradle schema-load-failure "schema-load failure blocks Gradle" DOCKER_FAIL_SCHEMA
check_hook_removal_failure
check_running_removal_failure
check_unremoved_container
check_absent_container
check_inspection_failure
check_direct_schema_preflight
check_deletion_gate
check_master_gate
check_integration_reset_failure

printf '\n%d passed, %d failed\n' "$passes" "$failures"
if (( failures > 0 )); then
    printf 'Failed contracts:\n'
    printf '  - %s\n' "${failure_names[@]}"
    exit 1
fi
