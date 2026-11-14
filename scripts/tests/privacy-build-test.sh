#!/usr/bin/env bash
# Check that production builds cannot omit the operator's project or privacy contact.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT
cd "$ROOT/frontend"
export VITE_SUPABASE_ANON_KEY=test-dummy
export VITE_SUPABASE_URL=http://127.0.0.1:1
export VITE_PRIVACY_CONTROLLER_NAME='Example Operator'
export VITE_PRIVACY_CONTACT_EMAIL=privacy@example.com

expect_refusal() {
    local expected="$1"
    shift
    if "$@" >"$LOG" 2>&1; then
        echo 'Expected a missing/invalid build configuration to refuse the build.' >&2
        exit 1
    fi
    grep -q "$expected" "$LOG"
}
privacy_error='Set VITE_PRIVACY_CONTROLLER_NAME and a valid VITE_PRIVACY_CONTACT_EMAIL'
expect_refusal 'VITE_SUPABASE_URL is not set' env -u VITE_SUPABASE_URL npm exec -- vite build
expect_refusal "$privacy_error" env -u VITE_PRIVACY_CONTROLLER_NAME npm exec -- vite build
expect_refusal "$privacy_error" env -u VITE_PRIVACY_CONTACT_EMAIL npm exec -- vite build
expect_refusal "$privacy_error" env VITE_PRIVACY_CONTACT_EMAIL=invalid npm exec -- vite build
npm run build >"$LOG" 2>&1
echo 'Public build checks: 4 refusals and a valid production build passed.'
