# Portable software factory

Copy **this entire folder** to use the vanilla workflow in another project. It has no runtime dependency on its parent repository, other skills, named agents, company rules, trackers or model providers. Keep the folder name `software-factory` for Agent Skills hosts that require it to match the declared name.

- [SKILL.md](SKILL.md): the complete intake → planning → own implementation → review/correction → exact-state acceptance → handoff → post-publication feedback workflow.
- [domain.md](domain.md): fillable project facts and owner decisions, not an invented security policy.
- [records](references/records.md): small shared record shapes.
- [walkthroughs](references/walkthroughs.md): independent contract-review fixtures, initially unexecuted.
- [checker](scripts/check_bundle.py) and [tests](tests/test_bundle.py): optional offline structural validation, Python 3.10+ standard library only. No dependency installation.

## Adopt in a project

1. Review the whole folder and the target project's governing instructions. Decide its owner, local home, explicit invocation/discovery and collision handling; placement or a worktree alone is not an activation barrier.
2. Copy the folder, retaining its internal layout. Do not run the parent repository's installer or copy unrelated agents, profiles, configuration or policies.
3. Fill material project-specific rows in `domain.md` from actual sources; keep the core generic. Use plain project-relative source paths in the domain table, not package-relative links escaping this folder. Empty values stay unknown and block affected actions only.
4. Reuse the operator's existing task authorization and settled decisions; do not add a brief/plan approval round for already authorized delivery. Planning-only work ends with the finalized plan; a subsequent implementation instruction approves it and starts scoped delivery. Review-only and planning-only invocations do not grant delivery permissions.
5. Start with one bounded approved real task. Walkthroughs are optional interpretation examples, not activation gates. Treat observations as usability feedback, not measured effectiveness.

The portable core owns the common process: parent/epic and prior-plan research, documentation/catalog searches where available, tagged research and requirement-source mapping, draft-first clarification, linked What / Why / How presentation and automatic independent slice/correction/final review under standing authorization. No extra validator or factory-only certificate is required. Confluence and Cortex are optional source integrations, not dependencies of this bundle; use applicable alternatives or record unavailability. Keep concrete project invariants, commands, paths and policy sources in `domain.md` or its local reference modules. For another project, start with this vanilla folder and a fresh domain supplement, not a populated supplement copied from a different project.

With Pi, the copied folder can live at `.agents/skills/software-factory/` or another admitted skill location. Invoke `/skill:software-factory` explicitly; `disable-model-invocation: true` keeps automatic model selection off in supporting hosts. Other hosts must establish their own discovery/invocation controls; this metadata is not a security boundary. No extension, installation, reload, tool permission or model call is performed by this folder.

## Project-specific derivatives

Two separately maintained skills derive from this vanilla bundle: the **Planning Cloud** factory and the **Versioning Service** factory. Each keeps its project-specific facts in its own domain supplement. Neither is included in or required by this portable bundle; upstream changes are reviewed and propagated deliberately, not synchronized automatically. For a new project, copy this vanilla bundle with a fresh domain supplement rather than either populated derivative.

## Optional local checks

From the copied folder (through the host's approved resource wrapper where required):

```bash
python3 -B scripts/check_bundle.py
python3 -B -m unittest discover -s tests -p 'test_bundle.py' -v
```

The checker also accepts an explicit folder path. It reads only the five shipped Markdown documents (at most 1 MiB each), checks supported simple inline links (no nested brackets/parentheses or multiline labels) stay inside the folder and resolve to files, and reports controlled errors. HTTP(S) citations are not fetched. It does not implement a full Markdown/frontmatter parser, validate anchor headings, inspect future extra files, authenticate policies or receipts, validate task results or prove agent compliance. Local path checks are cooperative checks, not a filesystem sandbox or tamper-proof attestation.

The tests cover the checker, and the complete-bundle test checks an isolated copy from an unrelated working directory. Symlink regressions require host support and permission to create symlinks. They do not call a model, run a product suite, install or publish anything. Actual task checks come from the admitted project/domain, not this checker.

## Extend only when needed

Keep project facts/checks in `domain.md`; add referenced local documentation only for a supported gap. Add executable helpers under `scripts/` with focused tests under `tests/`, retaining copyability and explicit execution approval. Extend the checker if new documents need structural coverage. A future thin host adapter may provide concrete automation, but the core must remain usable without it. This release contains no dispatcher, persistent ledger, automatic publication, automatic learning or enforcement hooks.
