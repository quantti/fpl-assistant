# Five-minute release path

**PROGRESS:** Research documented and tracked in [KAR-78](https://linear.app/quantti/issue/KAR-78/deliver-verified-releases-within-five-minutes-of-merge); implementation not started.

## Linear ticket packet

Title: Deliver verified releases within five minutes of merge
Team: Kari
Identifier: [KAR-78](https://linear.app/quantti/issue/KAR-78/deliver-verified-releases-within-five-minutes-of-merge)

Reduce merge-to-production latency to approximately five minutes without weakening release checks, adding production Machines, increasing unsafe build concurrency, or deploying unverified builds. Reuse a verified immutable release candidate instead of repeating its build and checks after merge. Establish a fail-closed correspondence between the merged source, successful verification and the deployed image. Preserve serialized deployments, health checks and external functional smoke tests. Coordinate with ongoing test-load reduction rather than duplicating that work.

This document contains the ticket scope, evidence, proposed approach and acceptance criteria. It is a proposal, not approval to change production or the current test policy.

## Owner requirements

- Target approximately five minutes for releases; explicitly measure merge-to-verified-production separately from PR verification time and total lead time.
- Keep a meaningful check before publication; speed must not come from publishing broken or unverified builds.
- No additional production Machines. Do not rely on unsafe parallel builds/tests or increased memory pressure.
- Test-load reduction and test optimization are already in progress elsewhere. Coordinate the boundary.
- No application or deployment changes were requested during this research/documentation task.

## Observed baseline

Read-only GitHub Actions inspection found that remote master had already moved beyond the local checkout's workflow. The remote workflow, not the stale local copy, is the baseline here.

On inspected remote master, PR checks are lightweight; full backend tests gate application-bearing master deployments. Docs-only changes skip application tests/deployment. A superseded-application-revision guard runs inside the serialized deployment slot. Deployment is not cancelled in progress. This differs from the older project convention of full verification on both PR and merge and must be reconciled deliberately during implementation.

| Run | Backend suite | Deploy job | Notes |
| --- | --- | --- | --- |
| [39203830215](https://github.com/quantti/fpl-assistant/actions/runs/39203830215) | 11m 58s | 2m 28s | Test job 13m 17s; test start to deploy completion 15m 47s. |
| [39201872228](https://github.com/quantti/fpl-assistant/actions/runs/39201872228) | 11m 41s | 2m 34s | Another completed publishing run. |
| [39201492110](https://github.com/quantti/fpl-assistant/actions/runs/39201492110) | 8m 23s | 5s | Deploy action skipped; not a valid deployment-duration sample. |

The latest inspected publishing run used commit `eee62232508c48f9740ab43706150e73e23175b1`. Its frontend build took 16s, lint/format 18s, and the Fly deploy command 2m 17s. These are a small sample, not a percentile or performance guarantee.

The runtime is one always-on Fly Machine, 1 GiB RAM and one CPU, with `--ha=false`. The Dockerfile rebuilds the frontend after CI has already built it and runs `bootJar -x test` in the remote image build. The final check curls the public custom domain's `/actuator/health`. Thus packaging is repeated and CI does not currently exercise the exact final container as a release candidate.

Conclusion: backend verification dominates current merge-to-production time. The observed deployment itself already fits within five minutes. Docker caching alone cannot remove the current approximately twelve-minute verification wait.

## Proposed design

### 1. Verify before merge; retain the evidence

Prepare a release candidate from the PR combined with the latest master, not only the PR branch in isolation. Perform the agreed full verification before permitting merge. Keep this as one bounded verification/build pipeline, not concurrent heavyweight work on a constrained host.

Only one merge candidate is admitted at a time in the initial design. If master changes, its prior acceptance is invalidated unless an explicitly verified equivalence policy proves the relevant content unchanged. Do not implement a general-purpose merge-train service for this ticket.

GitHub's native merge queue is free for public organization repositories, but private organization repositories require Enterprise Cloud. It is not available on this private Free repository. A controlled merge command/workflow is the proposed minimal alternative. Without branch protection, a repository owner can bypass it; document that residual operational limitation rather than claiming enforced protection.

### 2. Build once and publish the same image

Build the frontend and backend release package once, assemble the runtime image and run a bounded container smoke test against a disposable test database. Retain an immutable image digest and verification evidence. Packaging must reuse the verified outputs rather than independently rebuilding them during deployment.

After merge, deploy that image via Fly's `--image` support. Never use a mutable `latest` tag as the release identity. Bind verification to source, packaging inputs and the image digest. A build/check failure must not produce an eligible release.

The implementation must choose trusted storage and authentication for the candidate image/evidence. PR code must not gain production credentials or permission to forge its own approval. Missing evidence, expired/missing artifacts, insufficient permissions or registry failure must stop publication rather than silently fall back to an unchecked build.

### 3. Fail-closed correspondence gate after merge

Squash merging changes commit SHA, so equality between a PR SHA and the merged SHA is not a valid requirement by itself. Compare the actual source tree and verify the trusted successful run and immutable image linked to it. Define provenance for configuration, submodules or other non-tree inputs if applicable; do not assert tree equality proves arbitrary build-input equality.

The gate must reject a stale base, changed source, a wrong candidate/image, incomplete/failed checks and unverifiable evidence. A change in master after candidate verification must never silently inherit a previous candidate's green result. The controlled merge operation must address the check-to-merge race, including the fact that checking only the PR head does not prove its base is unchanged.

Keep the existing superseded-release guard inside the serialized deployment slot. Do not let an older release overwrite a newer application revision. Docs-only behavior must remain explicitly defined and regression-tested.

### 4. Preserve a small post-merge publication path

After successful correspondence checks:

1. Acquire the existing non-cancelling deployment slot.
2. Revalidate supersession and candidate evidence.
3. Deploy the immutable prebuilt image to the existing single Machine.
4. Wait for Fly health checks.
5. Check the public custom domain externally.
6. Run a bounded functional smoke: frontend HTML and its referenced asset load successfully; a protected API refuses an unauthenticated request.
7. Confirm the running release identity, not merely that an old instance answered health.

The exact endpoint and identity mechanism should reuse existing facilities if available. Smoke must not call an LLM, consume quota, start sync, or mutate production data. A smoke failure marks the release unsuccessful, even if Fly accepted the update.

Retain the previous known-good image and a documented rollback procedure. Do not promise automatic rollback without implementing and testing it. An image rollback cannot undo an incompatible database migration: schema/startup changes must remain backward-compatible or receive a separately approved release procedure.

## Safety boundaries and exclusions

- No `immediate` strategy, skipped health checks or shortened safety waits without measurements.
- No second production Machine, blue/green environment or canary fleet.
- A single-Machine restart can interrupt service. Five-minute release latency is not a zero-downtime guarantee, nor a guarantee that every bad release is detectable before restart.
- No extra test workers, simultaneous heavyweight builds, larger runner or host-capacity assumptions as the primary solution.
- No replacing the complete required verification with a guessed changed-test subset or a nightly-only full suite.
- No tests against Supabase; container checks use disposable seeded databases.
- Preserve graceful worker drain, ownership fencing and shutdown safety. Do not trim the 60-second kill timeout just to improve timing.
- No deployment, sync activation or merge during this documentation task.
- Existing local uncommitted application/recovery work is outside scope.

## Bounded implementation sequence

1. Reconcile the latest workflow and ongoing test-optimization work; record authoritative policy, source identity, timing boundaries and baseline samples. Verify by reviewing current remote configuration and run data.
2. Add release-candidate packaging and immutable evidence with focused regression tests. Verify successful candidates and deliberate build/test/container-smoke failures on disposable infrastructure.
3. Add the controlled merge and post-merge correspondence gate. Verify stale-base, check-to-merge race, squash/tree identity, wrong digest, missing/failed/untrusted evidence, docs-only and supersession cases without touching production.
4. Switch deployment to the verified prebuilt image while preserving serialized updates. Verify trusted artifact retrieval, exact image selection, external smoke and release identity. Reconcile project instructions/status with the approved policy.
5. Measure owner-approved real releases and rehearse rollback through an approved safe procedure. Record whether the five-minute objective is met and any remaining bottleneck.

Each implementation unit gets its own focused checks and review. Do not hand this entire design to one unbounded implementation agent. Follow the project's branch/PR process; GitHub remains the full-suite gate.

## Acceptance criteria

- Normal releases target at most five minutes from merge event to successful external checks and confirmed release identity. Record queue wait separately but include it in end-to-end elapsed time; do not hide waiting to meet the target.
- Report at least five representative application releases, each with verification time, packaging time, deployment-slot wait, Fly update/start time and smoke time. Report misses and cold-cache behavior; this initial sample is not a statistical SLA.
- Required verification completes successfully before merge/publication of the corresponding candidate; the post-merge path does not rerun the entire suite solely because a squash commit has a different SHA.
- The deployed digest is exactly the verified candidate digest. No second application compilation occurs in the normal deployment path.
- Automated focused regressions reject stale/changed source, base races, missing/failed/untrusted evidence, wrong images and superseded releases.
- Failed builds/checks cannot reach production. Failed deployment/smoke cannot be reported as success. Normal safe failure leaves the prior release in place where possible; rollback limits are documented.
- Existing single-Machine count and safe build/test resource limits remain unchanged. Fly health checks, external-domain checks and graceful shutdown/fencing remain enabled.
- The previous known-good image is identifiable and retained; rollback instructions cover backward-compatible DB requirements.
- The controlled merge path and its owner-bypass limitation are documented. No paid GitHub merge queue is assumed.

## Expected benefit and limits

Moving verification before merge can plausibly reduce merge-to-production below five minutes given the observed 2.5-minute deploy jobs. This relocates waiting; it does not eliminate PR verification time. Faster total lead time still depends on the independent test-optimization work. Reusing a prebuilt image reduces duplicate work and strengthens artifact identity, but its actual timing benefit remains to be measured.

Cache tuning, JVM startup improvements and Docker layering are secondary optimizations only if subsequent measurements justify them. Do not add native-image compilation, AOT infrastructure, a home-grown queue service or broad workflow abstractions for this objective.

## References

- Current workflow: https://github.com/quantti/fpl-assistant/blob/master/.github/workflows/deploy.yml (mutable; baseline commit and runs above identify the inspected version).
- GitHub merge queue availability: https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue
- PR merge-ref and event semantics: https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows
- Fly prebuilt-image deployment: https://fly.io/docs/launch/deploy/
- Fly deployment strategy tradeoffs: https://fly.io/zero-downtime-deploys/
- Docker image testing before push: https://docs.docker.com/build/ci/github-actions/test-before-push/
- Build cache options, if measurement later warrants them: https://docs.docker.com/build/cache/backends/gha/

Research used repository reads, read-only GitHub API queries and public documentation. No tests, builds, production queries or deployments were performed. Created KAR-78 through Linear's GraphQL API using the credential location documented in AGENTS.md. The issue includes the requirements, baseline, design, safety boundaries, implementation sequence and acceptance criteria below the ticket packet.
