#!/usr/bin/env bash
#
# Regenerates backend/src/test/resources/db/tapas-schema.sql: the DDL of the six tables this
# project reads but does not own. They belong to tapas-fpl-app and have no DDL in this
# repository, so an empty test database has no `player` table at all — the failure is
# `relation "player" does not exist`, not a missing row.
#
# The output is a GENERATED FILE. Never hand-edit it: a hand-edit is exactly the schema drift
# this script exists to prevent (CI green, production SQL broken). Regenerate instead, and
# re-run after any tapas migration.
#
#   scripts/dump-test-schema.sh           # regenerate the committed dump
#   scripts/dump-test-schema.sh --check   # fail if the committed dump differs from live
#
# Tables this project's own startup DDL creates (fpl_embeddings, pl_match_result,
# fpl_player_price, fpl_daily_price, and the Phase 5a user tables) are deliberately NOT here:
# they are created by @PostConstruct at boot and would freeze a schema the code already owns.
#
# Runs pg_dump inside a container so no host Postgres client is needed and the client version
# matches Supabase (17.6). Connects on the SESSION pooler (5432), not the transaction pooler
# (6543) the application uses: pg_dump needs a session, and the direct host is IPv6-only, which
# most WSL2 setups cannot reach.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="$REPO_ROOT/backend/src/test/resources/db/tapas-schema.sql"

TABLES=(season team player gameweek fixture player_fixture_stats)

PG_IMAGE="${PG_IMAGE:-postgres:17}"
TAPAS_DB_HOST="${TAPAS_DB_HOST:-aws-1-eu-west-1.pooler.supabase.com}"
TAPAS_DB_PORT="${TAPAS_DB_PORT:-5432}"
TAPAS_DB_NAME="${TAPAS_DB_NAME:-postgres}"

check_mode=false
case "${1:-}" in
    --check) check_mode=true ;;
    "") ;;
    *) echo "usage: $(basename "$0") [--check]" >&2; exit 2 ;;
esac

# Supabase credentials, not the test database's: this script reads the real schema. .env.test
# points at the local container, so it is only the fallback for setups predating the split.
if [[ -z "${DB_USER:-}" || -z "${DB_PASSWORD:-}" ]]; then
    env_file=""
    for candidate in "$REPO_ROOT/backend/.env.supabase" "$REPO_ROOT/backend/.env.test"; do
        [[ -f "$candidate" ]] && { env_file="$candidate"; break; }
    done
    if [[ -z "$env_file" ]]; then
        echo "DB_USER/DB_PASSWORD not set and backend/.env.supabase does not exist." >&2
        echo "Copy backend/.env.supabase.example and fill in the Supabase credentials." >&2
        exit 1
    fi
    set -a; source "$env_file"; set +a
fi

# Trigger functions live outside the tables pg_dump -t selects, but the triggers it emits
# reference them: without this section the dump fails to load with "function
# public.update_pfs_updated_at() does not exist".
trigger_functions() {
    local table_list
    table_list=$(printf "'%s'," "${TABLES[@]}"); table_list="${table_list%,}"
    docker run --rm -i -e PGPASSWORD="$DB_PASSWORD" "$PG_IMAGE" \
        psql -h "$TAPAS_DB_HOST" -p "$TAPAS_DB_PORT" -U "$DB_USER" -d "$TAPAS_DB_NAME" \
        -v ON_ERROR_STOP=1 -tA -c "
            SELECT DISTINCT pg_get_functiondef(t.tgfoid) || ';'
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE NOT t.tgisinternal AND n.nspname = 'public'
              AND c.relname IN ($table_list)
            ORDER BY 1"
}

schema_dump() {
    local args=()
    for table in "${TABLES[@]}"; do args+=(-t "public.$table"); done
    docker run --rm -e PGPASSWORD="$DB_PASSWORD" "$PG_IMAGE" \
        pg_dump -h "$TAPAS_DB_HOST" -p "$TAPAS_DB_PORT" -U "$DB_USER" -d "$TAPAS_DB_NAME" \
        --schema-only --no-owner --no-privileges --no-tablespaces "${args[@]}" \
    | grep -v -e '^\\restrict ' -e '^\\unrestrict ' \
               -e '^-- Dumped from database version' -e '^-- Dumped by pg_dump version'
    # Those four lines are stripped because they are not schema: pg_dump emits a NEW RANDOM
    # \restrict token on every run (a psql meta-command, not DDL), and the two version comments
    # change whenever the client image is updated. Left in, --check would report drift on every
    # run and stop meaning anything.
}

generate() {
    cat <<HEADER
--
-- GENERATED FILE — DO NOT HAND-EDIT.
--
-- Source: tapas-fpl-app's tables in the shared Supabase database.
-- Regenerate: scripts/dump-test-schema.sh   (verify: scripts/dump-test-schema.sh --check)
-- Re-run after any tapas migration; a stale copy means CI passes while production SQL breaks.
--
-- Contains ONLY the six tables this project reads and does not own:
--   ${TABLES[*]}
-- This project's own tables are created by its @PostConstruct startup DDL and must never be
-- added here. The pgvector extension is created separately by the CI job and the local
-- setup documented in docs/DEVELOPMENT.md.
--
-- Schema only: no rows. Tests seed the data they assert on.
--
HEADER
    echo
    trigger_functions
    schema_dump
}

if [[ "$check_mode" == true ]]; then
    if [[ ! -f "$OUTPUT" ]]; then
        echo "FAIL: $OUTPUT does not exist. Run scripts/dump-test-schema.sh to create it." >&2
        exit 1
    fi
    tmp="$(mktemp)"
    trap 'rm -f "$tmp"' EXIT
    generate > "$tmp"
    if diff -u "$OUTPUT" "$tmp" > /dev/null; then
        echo "OK: ${OUTPUT#"$REPO_ROOT/"} matches the live tapas schema."
    else
        echo "DRIFT: the committed dump no longer matches the live tapas schema." >&2
        diff -u "$OUTPUT" "$tmp" >&2 || true
        echo >&2
        echo "Fix: re-run scripts/dump-test-schema.sh, review the diff, and commit it." >&2
        exit 1
    fi
else
    mkdir -p "$(dirname "$OUTPUT")"
    generate > "$OUTPUT"
    echo "Wrote ${OUTPUT#"$REPO_ROOT/"} ($(wc -l < "$OUTPUT") lines, tables: ${TABLES[*]})"
fi
