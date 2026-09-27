#!/usr/bin/env bash
#
# The local test database: the same image CI uses, created empty and thrown away.
#
#   scripts/test-db.sh up      # start it (if needed) and load the schema
#   scripts/test-db.sh reset   # destroy and recreate it — the guaranteed-clean path
#   scripts/test-db.sh down    # stop and remove it
#
# Nothing in the suite depends on state surviving between runs, so `reset` is never a repair
# step you need to think about: if a run leaves the database in a state you are unsure of,
# throw it away.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCHEMA="$REPO_ROOT/backend/src/test/resources/db/shared-stats-schema.sql"

CONTAINER="${TEST_DB_CONTAINER:-fpl-test-db}"
IMAGE="${TEST_DB_IMAGE:-pgvector/pgvector:pg17}"
PORT="${TEST_DB_PORT:-55432}"

require_schema() {
    if [[ ! -r "$SCHEMA" ]]; then
        echo "Test schema is missing or unreadable: $SCHEMA" >&2
        return 1
    fi
}

# Returns 0 when present, 1 when absent and 2 when Docker could not inspect it.
inspect_container() {
    local output
    if output="$(docker container inspect --format '{{.State.Running}}' "$CONTAINER" 2>&1)"; then
        CONTAINER_RUNNING="$output"
        return 0
    fi
    if [[ "$output" == *"No such container: $CONTAINER"* ]]; then
        CONTAINER_RUNNING=false
        return 1
    fi
    echo "Could not inspect test container $CONTAINER: $output" >&2
    return 2
}

remove_container() {
    local status
    if inspect_container; then
        docker rm -f "$CONTAINER" >/dev/null
        if inspect_container; then
            echo "Docker reported that $CONTAINER was removed, but it still exists." >&2
            return 1
        else
            status=$?
            [[ "$status" -eq 1 ]] || return "$status"
        fi
    else
        status=$?
        [[ "$status" -eq 1 ]] || return "$status"
    fi
}

start() {
    require_schema

    local status
    if inspect_container; then
        if [[ "$CONTAINER_RUNNING" == true ]]; then
            echo "Container $CONTAINER is already running on port $PORT."
        else
            remove_container
            docker run -d --name "$CONTAINER" \
                -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=postgres \
                -p "127.0.0.1:$PORT:5432" "$IMAGE" \
                -c shared_buffers=32MB -c work_mem=2MB \
                -c maintenance_work_mem=32MB -c max_connections=60 >/dev/null
            echo "Started $CONTAINER ($IMAGE) on port $PORT."
        fi
    else
        status=$?
        [[ "$status" -eq 1 ]] || return "$status"
        docker run -d --name "$CONTAINER" \
            -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=postgres \
            -p "127.0.0.1:$PORT:5432" "$IMAGE" \
            -c shared_buffers=32MB -c work_mem=2MB \
            -c maintenance_work_mem=32MB -c max_connections=60 >/dev/null
        echo "Started $CONTAINER ($IMAGE) on port $PORT."
    fi

    for _ in $(seq 1 30); do
        if docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done

    docker exec -i "$CONTAINER" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q \
        -c "CREATE EXTENSION IF NOT EXISTS vector" >/dev/null

    # The dump is plain CREATE TABLE (pg_dump emits no IF NOT EXISTS), so re-loading it into a
    # container that already has the schema fails. `up` is therefore safe to call repeatedly;
    # `reset` is the way to reload from a clean container.
    if [[ -n "$(docker exec -i "$CONTAINER" psql -U postgres -d postgres -tAc \
                "SELECT to_regclass('public.season')" | tr -d '[:space:]')" ]]; then
        echo "Schema already loaded (use 'reset' to start over)."
    else
        # stdout is silenced because the dump's set_config() call prints a result set.
        docker exec -i "$CONTAINER" psql -U postgres -d postgres -v ON_ERROR_STOP=1 -q < "$SCHEMA" >/dev/null
        echo "Loaded $(basename "$SCHEMA"). The project's own tables are created by the app at boot."
    fi
    echo
    echo "  DATABASE_URL=jdbc:postgresql://localhost:$PORT/postgres  DB_USER=postgres  DB_PASSWORD=postgres"
}

case "${1:-up}" in
    up)    start ;;
    reset) require_schema; remove_container; start ;;
    down)  remove_container; echo "Removed $CONTAINER." ;;
    *)     echo "usage: $(basename "$0") [up|reset|down]" >&2; exit 2 ;;
esac
