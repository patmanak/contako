---
name: contako-sync-investigation
description: Investigate Contako sync failures, pending Android copies, rejected Proton edits or divergence between Room, Proton and Android. Use to identify a causal boundary and verify a scoped fix.
---

# Investigate synchronization

Read the repository AGENTS.md and optional AGENTS.local.md first. Paths below
are relative to the repository root. Select the relevant case in
`docs/FUNCTIONAL_TEST_PLAN.md` and the contract in `docs/SYNCHRONIZATION.md` or
`docs/CONTACTS.md`; do not reload the full history to reconstruct a task.

## Locate the failure

Establish the changed field family, originating peer, expected result and exact
build. Device/account authorization is separate from permission to change code.
Start from available sanitized evidence and the production call path. Trace:

`editor/provider -> durable Room intent -> Proton mutation/ack -> Android projection -> dashboard`

Find the first failed boundary rather than treating the final banner as the cause.
Separate a Proton outbox mutation, an Android projection obligation and an account
problem; counts describe different units. A native visible contact or zero outbox
count MUST NOT be used to adopt a baseline or clear outstanding intent.

State the leading hypothesis and what observation would disprove it. Compare a
working and failing path, including imported data shape, source-owned provider
rows, aggregation, version/epoch, process interruption and pending receipts.
Change one cause at a time. If evidence rejects a fix, reconsider the hypothesis
instead of adding another workaround or broadening a comparison guard.

Use existing closed diagnostics. If insufficient, add only typed categories or
aggregates at the missing boundary, isolate observer failure, and keep payloads,
identifiers, raw exceptions and credentials out. Do not follow upstream examples
that dump inputs, outputs, environment variables or signing material.

## Correct and qualify

Fix the responsible boundary while preserving account ownership, durable intent,
atomicity, cancellation and unknown-field preservation. A missing proof stays
missing; do not fabricate a timestamp, force DIRTY clean or accept unmatched bytes.
Reproduce the defect with invented data of the same relevant shape. Use a focused
regression if it guards the behavior; a real reproduction can be the initial
failing evidence when a synthetic model would miss the defect.

Exercise the original sequence on an authorized environment and independently
compare intended and untouched fields in applicable peers. Distinguish ordinary
automatic convergence, manual retry and repair recovery. Check restart/no-change
and deletion only where relevant to the fix and authorized. If physical/Web
verification is blocked, finish safe source/build work and report that limit.

Record build, scoped result and fixture cleanup in the ignored local result
template from `docs/QA.md`. Maintain known limitations without task logs;
update the reusable functional
case when acceptance changes. Preserve technical characteristics, not private
contacts. Do not claim an OEM or signature variant from another case.
