---
name: contako-code-review
description: Review Contako changes or plan safe simplification, dependency reuse and security maintenance. Check requested behavior separately from architectural invariants and report evidence-backed findings.
---

# Review code and maintenance changes

Read the repository AGENTS.md and optional AGENTS.local.md. Resolve the review
scope from the request and Git state; include relevant uncommitted/untracked
changes instead of silently reviewing HEAD alone. Paths below are repository-relative.

## Two questions

1. Does the actual behavior satisfy the request and applicable product decision?
   Trace production callers, UI and recovery paths. A helper or unit test existing
   is not proof that the runtime uses it. Flag scope expansion separately.
2. Does the change retain the data, privacy and interoperability contracts?
   Review durable transaction boundaries, acknowledgement/retry/cancellation,
   account/provider ownership, hidden fields and untrusted-input bounds. Follow
   errors through the caller; do not evaluate a boundary in isolation.

Review both questions directly. Delegation is optional only when separately
authorized and useful; the workflow does not require agents or an external tracker.

## Simplification and dependency reuse

Inspect callers before removing apparently dead code: Android manifests/resources,
serialization, reflection/R8 rules, migration history, runtime registries and
instrumentation may be references that ordinary text searches miss.

For duplicates, compare all callers' semantics, side effects, error/cancellation
behavior and transaction ordering before extracting a shared policy. Keep useful
domain boundaries; fewer files or lines is not a correctness argument. Separate
behavior changes from refactoring and do not delete existing work to enforce TDD.

For Android/Proton reuse, inspect the pinned catalog and maintained public API or
source matching that version. Distinguish an available upstream abstraction from
a compatible replacement. Dependency changes require a reviewed graph, strict
checksums, complete notices and the affected minified/crypto/device evidence.
Do not import an entire framework to replace a narrow adapter.

For security, identify the input, trust boundary and reachable production path.
Separate confirmed defects, plausible risks and unknown reachability; neither
an advisory count nor no detected match establishes exploitability or clearance.

## Findings and completion

For each actionable finding, provide location, concrete triggering condition,
impact and supporting evidence. Label style/design judgments as such. Prefer
existing checks over duplicating lint feedback. A requested review is not blanket
authority to implement every suggestion or publish its private evidence.

When corrections are authorized, choose a bounded slice, run appropriate checks
and inspect the resulting diff. Record actual commands/results and unverified
limits. Re-read acceptance before declaring completion; do not use previous test
totals as evidence for changed code. Keep public contracts in `docs/DECISIONS.md`
and implementation limits in `docs/KNOWN_LIMITATIONS.md`. Keep task state and
execution logs local; do not create a parallel public plan archive.
