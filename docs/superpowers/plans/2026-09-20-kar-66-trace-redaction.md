# KAR-66 — Stack-safe trace redaction

**PROGRESS:** Task 3/3 done.

Ticket: https://linear.app/quantti/issue/KAR-66/fix-chat-http-500-caused-by-trace-redaction-stack-overflow

## Evidence and scope

Production serves the page and health endpoint but chat returns HTTP 500. The recent Fly log
buffer contains recursive `java.util.regex.Pattern` frames at 07:19:28 UTC on 2026-09-20, followed
by the five-minute chat timeout; its exception header was outside the retained buffer.

A standalone Temurin 25.0.4.1 probe reproduced `StackOverflowError` using the deployed
`ChatTraceCollector.QUOTED_PAIR` and the checked-in 31,324-character system prompt. A synthetic
32,011-character quoted value also failed. Making the two quoted-string repetitions possessive
(`*+`) passed both inputs. The defect arrived in `3259810`, not the unmerged KAR-53 changes.
Trace collection starts synchronously before the provider call and before the controller returns
its SSE stream, so a fatal regex error can escape as HTTP 500 instead of an SSE error event.

Fix the regex, not the prompt or JVM stack. Preserve redaction, trace content, provider behavior
and the existing error contract. No disabling tracing, catching fatal JVM errors, schema changes,
new dependencies or unrelated telemetry redesign. No production changes before owner merge and
the ordinary deployment workflow. The temporary read-only diagnostics PR #122 was closed and its
raw log runs deleted; only the non-sensitive findings were retained in Linear.

## Task 1 — Regression tests

Extend `ChatTraceInstrumentationTest` with the actual classpath system prompt and long quoted
ordinary values, keys and escaped secret values. Assert exact retained/redacted content, not just
absence of an exception. Exercise an instrumented `ChatService` turn with the real system prompt
and a stub provider, with a bounded wait. Keep existing quoted-credential and metadata tests.

The pre-fix standalone Java reproduction already failed. This host has no JDK; the new JUnit
regressions run on PR CI, not against production. One bounded subagent only, no nested or parallel
agents and no local builds/test suites alongside it.

Outcome: six regressions added before changing the regex, using the classpath prompt, long
ordinary keys/values, escaped credentials, unmatched text and a complete stubbed instrumented
turn. All assert exact content. Existing tests are unchanged. No local test run was claimed;
compilation and execution await the PR run.

## Task 2 — Minimal fix and publication

Use possessive repetition for both quoted-string interiors. Their alternatives are disjoint and
cannot consume the closing quote, so backtracking is unnecessary; document why `*+` is deliberate.
Inspect the diff, record evidence in `docs/STATUS.md`, commit on
`fix/kar-66-trace-redaction`, push and open a PR targeting master with `Closes KAR-66`.

Verification: PR CI compiles and runs the trace/controller regressions and complete backend
suite against disposable PostgreSQL, and builds/checks the frontend. Never run tests against
Supabase or change the checked-in Gradle resource limits.

Outcome: [PR #123](https://github.com/quantti/fpl-assistant/pull/123), commit `06453dd`.
CI run `35498149340` passed the complete backend suite, frontend production build, lint and
format checks. The runtime change is exactly two possessive quantifiers plus their explanation.

## Task 3 — Review and handoff

Review the published PR using one read-only subagent. Apply any findings as new commits and
re-review; keep secrets redacted and ordinary text intact. Require green final CI, update Linear
to In Review and leave merging to the owner. After normal deployment, the owner must verify an
authenticated chat response; a healthy static page alone is not proof that this incident is fixed.

Outcome: independent review of published `06453dd` found no blockers and confirmed both matching
semantics and the short-user-message path. Its wording suggestion clarified that the system
prompt is always included even for short user messages; no behavior changed after review.
CI `35498149340` reports 1,255 backend tests, zero failures/errors/skips, including all 12 trace
instrumentation tests. Frontend build/lint/format also passed. The owner merged PR #123 at
`e123272` while the comment/documentation clarification was being published, so that clarification
is a separate follow-up with no behavioral change. Workflow `35498621880` passed tests,
deployment and the external health smoke; a further health read at 08:15 UTC returned 200 / UP.
The owner still needs to confirm authenticated chat. No manual deployment, restart or database
change was performed.
