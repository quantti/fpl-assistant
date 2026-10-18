# Minimal task records

Use these headings in an existing approved evidence location, not a new database. They are records, not machine-enforced schemas. Link shared authority/state instead of copying fields into every handoff. Retain material history with access/redaction/retention rules; do not store secrets or private reasoning. Begin with only the records needed for the selected stage.

## Brief

- Task and mode; accepted outcome/source; distinct requirement IDs; applicable parent/epic, full-plan and RFC/ADR references with later operator amendments.
- Actual workspace, exact candidate/base and admitted inputs with revisions/digests; material environment and source limits. HEAD alone is insufficient for dirty/untracked relevant inputs.
- Owned paths, dependencies and selected competing-change state/order; independence of any parallel slices.
- Existing operator authorization and scope, permitted local writes/commits, correction class and writer topology; ordinary independent review uses standing authorization, not another approval.
- Required project observation boundaries/checks, protected decisions and separate remaining gates; no mandatory extra validator or certificate.
- Carry forward existing round/time/cost/concurrency bounds and evidence/retention conventions; missing optional factory fields do not create approval gates.
- Per-slice readiness and unresolved choices. `ready` is not writing authority.

## Intake row

Question → material choice → fact/inference/decision → owning source @ revision/date → query/corpus/access/pagination limits → status (`ready`/`needs-decision`/`blocked`) → recheck trigger.

## Research

Save in the project's established research location, naming and document language, with its existing tag syntax. Reuse relevant issue, service/domain and behavior tags; search prior research by tags as well as names/text and verify the saved document is findable by those tags.

- Topic, date, scope, exact checkout/ref and linked issue/plan.
- Requirement-source map: invariant/outcome, exact source section or file/lines and revision/date, authority/status, current applicability and acceptance check or blocker.
- Source coverage: child issue and relevant parent hierarchy, local plans/research, documentation/decisions and service catalog. Record each as read, searched-not-found with terms/scope, not-applicable with reason or unavailable with the precise gap.
- Reconciled findings and prior decisions; distinguish current implementation, accepted intent, proposal, assumption and superseded guidance. For material previously accepted decisions, record continued applicability, the explicit amendment/supersession source or unresolved applicability; recency alone settles none of these. Explain conflicts and explicit operator amendments without silently dropping requirements.
- Brief pre-draft source summary in parent chat: linked earlier plans/governing sources, inherited decisions, explicit changes and material gaps. If a missed source is supplied later, record its impact on conclusions, requirements/scope, grill questions and worker/reviewer handoffs; update affected existing records and explain changed conclusions, not just the new citation. No separate form or approval round.
- Source links, access/search limits, deferred decisions, checks not run and implementation/review implications; no raw dumps, credentials or private transcripts.

The research supports a complete draft followed by decision-ordered clarification. Carry the finalized requirements and source references into every implementation/reviewer handoff; a child ticket or change summary alone is insufficient.

## Decision

Decision; accepted facts versus assumptions; impact; options/tradeoffs; recommendation; exact owner approval needed; affected blocked work. Do not repeat settled questions unless material inputs changed.

## Decision tree

Keep this compact in the conversation and the existing saved plan, not a new mandatory form:

- Settled: decision → source or actual user agreement; recommendations remain proposals.
- Open: material assumption/choice → concrete scenario or counterexample and consequence → prerequisite decisions/facts → current frontier or blocked reason.
- Deferred/out of scope: branch → explicit disposition and consequences; only dependent work is blocked.

After each answer, update affected branches and the plan/research; resolve ambiguity before advancing dependents. At closure, summarize which relevant behavior, lifecycle, failure, compatibility, security, dependency, acceptance and terminology boundaries were challenged and any remaining uncertainty. If no user question is needed, explain which source-backed decisions settle the exposed assumptions. This is an interview record, not another approval or a private reasoning transcript.

## Finding

- Stable task finding ID; tool/run/MR-note aliases and human/bot origin where applicable.
- Exact found-at state; trigger/impact; requirement-fit defect versus standards/risk/suggestion; applicable project severity.
- Verdict: `candidate`, `confirmed`, `refuted` or `inconclusive`; source/executed evidence, independent counterevidence and limits. Unsupported refutation is inconclusive, not closure.
- Status: `open`, `decision-required`, `deferred:<owner>`, `fixed-pending-verification`, `closed` or `superseded`.
- Closure: exact closing state and independent evidence; supersession: every carried-forward obligation.

Retain refuted, inconclusive and summary-omitted material entries. Confidence never suppresses a candidate. New material observations reopen affected closure, including same-tree evidence. A deferred item is not automatically an accepted residual.

## Review change note

Use the existing evidence location and project handoff format; link evidence rather than duplicating reports. Summarize intent and before/after behavior, preserved invariants and material assumptions, exact base/candidate and dirty inputs, affected callers/tests/configuration, actual checks and open risks. Bind material source pointers to the candidate. The note is navigation, not proof or a limit on independent review.

## Correction packet

Accepted finding IDs and exact correction diff → relevant accepted requirements/source map and actual check evidence → connected behavior/tests and carried-forward obligations → prior acceptance retained or reopened with reasons → exact closing state and independent recheck. Batch small independent documentation fixes only within the same authorized ownership/class; final integrated checks remain required.

## Retry decision

Previous attempt/run and reconciled state/ownership → stopped because X → material change Y with evidence → authorized next action Z → remaining accumulated bounds. If Y is absent or ownership is uncertain, no new attempt; record the blocker. A new session or model does not reset bounds.

## Terminal outcome

- `completed`: exact accepted state and final evidence; separate remaining publication/runtime gates.
- `blocked`: preserved partial state and findings; missing information/access/decision/evidence and continuation condition.
- `budget-exhausted`: preserved partial state and findings; consumed bound and next owner decision. Do not silently extend limits or report partial work as completed.

## Receipt

- Actual check/review producer and attempt; independence limitations. Existing project results/handoffs are valid formats; this record does not require another validator role.
- Exact candidate, admitted inputs and environment; requirement/finding IDs covered.
- Boundary/oracle source; expected versus observed behavior; plausible wrong behavior rejected and remaining blind spots.
- Actually executed command/check, result, pass/fail/skip counts when known and redacted artifact reference. Separate source inspection, executed evidence, writer claims and explicitly validated reused receipts; missing evidence is unverified, not zero failures.
- Independent review of requirement fit and governing standards; unresolved material gaps, acceptance decision/owner, permitted residuals and remaining external gates.

A receipt is not authenticated solely by naming an actor or digest. Read actual evidence before acceptance; same-tree adverse evidence or changed candidate/inputs can invalidate it. The bundle checker validates package links, not these records or their truth.

## Handoff

Intent / changes / proof / review focus / high-risk failure and debugging briefing / remaining gates. Include current exact state, accepted requirement-source map and later amendments, open obligations, actual artifact pointer and next authorized action; follow the project's required format. Present both the complete draft before grill questions and the finalized plan after answers directly in the parent chat: clickable plan link, durable path, supporting research, draft/final and local-only status, plus short plain-language Mitä / Miksi / Miten headings (or equivalents in the user's language). Explain outcome/scope, need and high-level approach/verification. The summary belongs in chat, not saved technical documents; a link, child artifact or check report alone is insufficient. No-question and already-authorized delivery paths still require presentation, not another approval. No automatic publication or private receipt dump.

## Feedback

Event/evidence → observed impact versus causal hypothesis → causal stage → smallest addition/removal/consolidation → independent valid-and-invalid-case evidence, false-positive/cost/coverage assessment → applicable owner adopt/reject/defer/inconclusive decision → rollback and follow-up.

Label construction observations separately from product-delivery observations. Unknown measurements stay unknown; no causal savings from anecdotes. Preserve required coverage when subtracting steps. Feedback grants no authority to edit instructions or redirect a live writer.
