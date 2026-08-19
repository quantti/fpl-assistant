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

start() {
    if [[ -n "$(docker ps -q -f "name=^${CONTAINER}$")" ]]; then
        echo "Container $CONTAINER is already running on port $PORT."
    else
        docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
        docker run -d --name "$CONTAINER" \
            -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=postgres \
            -p "$PORT:5432" "$IMAGE" >/dev/null
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
    # container that already has the schema fails. `up` is therefore safe to call repeatedly —
    # the pre-push hook does exactly that — and `reset` is the way to reload.
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
    reset) docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; start ;;
    down)  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; echo "Removed $CONTAINER." ;;
    *)     echo "usage: $(basename "$0") [up|reset|down]" >&2; exit 2 ;;
esac
