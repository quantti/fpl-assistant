#!/usr/bin/env bash
# Check that production builds cannot silently omit the privacy contact.
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
    if "$@" >"$LOG" 2>&1; then
        echo 'Expected a missing/invalid privacy configuration to refuse the build.' >&2
        exit 1
    fi
    grep -q 'Set VITE_PRIVACY_CONTROLLER_NAME and a valid VITE_PRIVACY_CONTACT_EMAIL' "$LOG"
}
expect_refusal env -u VITE_PRIVACY_CONTROLLER_NAME npm exec -- vite build
expect_refusal env -u VITE_PRIVACY_CONTACT_EMAIL npm exec -- vite build
expect_refusal env VITE_PRIVACY_CONTACT_EMAIL=invalid npm exec -- vite build
npm run build >"$LOG" 2>&1
echo 'Privacy build checks: 3 refusals and a valid production build passed.'
