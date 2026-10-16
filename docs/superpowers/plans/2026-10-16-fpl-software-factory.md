# FPL software-factory adoption

**PROGRESS:** Task 1/2 done — complete bundle copied, FPL domain and usage documentation written.

## Brief and source reconciliation

Owner request: bring the vanilla factory from `pi-config` into this repository as a
skill, with precise FPL domain knowledge. This is a bundle/documentation change, not
an invocation of the factory on a product ticket and not a runtime rollout.

Source: `quantti/pi-config` at `75cc2a52bdf9432dd7bf990fec2c9cc3527b7c00`,
`skills/software-factory/` (all seven files inspected). `git pull --ff-only` on the
source checkout refused divergent history; fetch succeeded. The bundle was extracted
from that exact remote commit, without merging, stashing or changing the dirty source
checkout. Target baseline: `0c933888c8b0409d052175d91ea5ebc6fc01d7e1`.
Worktree: `/home/vanka/dev/fpl-assistant-factory`, branch `feat/fpl-software-factory`.
The primary FPL checkout contains unrelated dirty sync work and an untracked credential
file. Preserve all of it; do not copy it or use blanket staging.

| Requirement | Sources and authority | Acceptance |
|---|---|---|
| A1: complete portable factory, explicitly invoked | Owner request; upstream README and SKILL at the pinned revision; installed Pi skills documentation | Copy all seven files under `.agents/skills/software-factory`; core/frontmatter, records, walkthroughs, checker and tests byte-identical upstream. No installer, global config or automatic invocation. |
| A2: accurate FPL specialization, not a second policy | `AGENTS.md` conventions/task execution/publication at target baseline; current runtime/config source where it distinguishes implementations | Populate `domain.md` with source/date, mandatory recheck, sensitive invariants, exceptions, code routing and concrete failure cases. Do not freeze active season/runtime mode or imply production authorization. |
| A3: real verification and deployment boundaries | `docs/DEVELOPMENT.md`, `backend/build.gradle`, `frontend/package.json`, `scripts/hooks/pre-push`, `.github/workflows/deploy.yml` | Give focused/local-safe commands, both host limits, test DB isolation, CI-only full backend suite, feature PR and human merge gates. No product build, live service or paid-model experiment for this adoption. |
| A4: maintainable discovery and evidence | Pi skills docs; upstream bundle checker contract; repository documentation layout | Explain explicit invocation/reload and collision handling in domain/DEVELOPMENT. Validate internal links, portable tests, project source paths and final diff. State that these do not prove agent behavior. |

Read: full upstream bundle; target AGENTS, current STATUS head and relevant October
cutover plan, complete OWNED-SYNC-OPERATIONS; DEVELOPMENT command/resource sections;
build scripts and actual class/test locations. Earlier specs and plans remain requirement
sources for future task intake, not blanket approvals. The primary and target checkout's
plan/analysis locations were searched for factory/vanilla work; no earlier matching plan
was found in that bounded search. No uniform research-tag convention was found by searching
`tags:`/`Tags:`/`**Tags` in repository Markdown; do not invent mandatory metadata. Use existing
issue/domain words in dated titles and search file contents, filenames and any actual tags.
No Linear ticket was supplied for this adoption; do not invent one. Confluence/Cortex are
not established sources for this repository; no integration installed or permission changed.

Important reconciliations: the AGENTS opening auth-deferred sentence is historical, not a
license to omit existing JWT/admin/quota controls. Older OFF83 and ACTIVE94 snapshots in
operations/cutover documents are dated evidence, not a live-state query. Legacy two-batch
fixture verification and the opt-in provisional importer are distinct paths; never impose
one path's finality condition on the other. AGENTS' current resource/publication rules take
precedence over older DEVELOPMENT full-suite command examples. The source's independent
review requirement remains intact; the domain cannot manufacture delegation authority or
replace unavailable independent evidence with self-approval.

## Final approach and decision tree

Settled: copy vanilla, specialize FPL (owner request); retain generic core (upstream
adoption contract); project-local explicit invocation (Pi documentation and upstream
frontmatter); no product/runtime changes (scope). Use `.agents/skills/software-factory`
with the matching declared name; no matching skill was found in the inspected project/
user skill locations. Check host discovery on actual use; installation paths alone are not
a security boundary. No global skill or host permission change.

Stress-test branches: stale domain versus current sources → re-read and reconcile;
Supabase/test confusion → refuse production tests/session SET; unknown metrics versus zero
→ keep source completeness gates; owned importer versus legacy receipts → path-specific
proof; dirty primary checkout → isolated worktree plus primary-plan search; unavailable
reviewer/integration → report affected evidence unavailable. No new user-owned behavior or
material policy choice remains for this documentation scope. No questionnaire needed.
Deferred: model execution/effectiveness evaluation, actual product-ticket rollout, production
mail/DB/sync activation, broader agent installation. None is implied by copying the skill.

## Bounded tasks

1. Copy the reviewed bundle unchanged except `domain.md`; fill the domain and add a short
   DEVELOPMENT entry. No new executable adapter, global AGENTS rule or project-wide default.
2. Run the offline checker and its unittest suite, compare untouched files to upstream,
   check every cited project file exists, review source fit and the exact diff, then commit
   and publish a feature PR under the repository's publication policy. Record inspection
   versus executed evidence separately. Documentation-only work needs no invented RED.

## Review and evidence

- `python3 -B scripts/check_bundle.py` from the copied bundle: passed. No fetched links,
  APIs or product runtime; this validates supported internal links, not source authority.
- `python3 -B -m unittest discover -s tests -p 'test_bundle.py' -v` from the copied bundle:
  12 passed, 0 failed/skipped, including isolated-copy/foreign-working-directory coverage.
- A read-only Python comparison confirmed the complete seven-file copy and six generic files
  byte-identical to pinned upstream. Domain source-path inspection resolved 27 cited repository
  paths, 18 package-qualified Java references and every named test navigation target.
- Source-fit inspection retained the ranking minute/evidence distinction, season/provenance
  exceptions, null/zero separation, legacy versus incremental finality, account isolation,
  no session SET on port 6543, resource limits and separate publication/runtime permissions.
  It also exposed a CI documentation trap: actual CI uses real embeddings and does not run
  frontend unit tests despite an outdated comment. The domain describes the executed steps,
  not that comment. No workflow or product source was changed.
- No regression RED was manufactured for this documentation-only copy/adaptation. No local
  backend/frontend build, database, provider call, package install, global settings change or
  live deployment. The offline Python suite is the only executed test suite for this change.
- This is construction/static-source review, not independent model review, actual Pi discovery,
  an end-to-end factory task or an effectiveness measurement. Those claims remain unverified.
  No independence claim is made for this author-performed inspection.
- Publication/final PR review pending. Source pi-config and primary FPL dirty inputs preserved.
