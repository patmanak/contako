# Deterministic contact compatibility bench

The JVM compatibility tests exercise the production vCard codec, Android mapper,
group policy, MIME router and durable mutation orchestrator against bounded,
invented data. They supplement the [three datasets](../dataset/README.md) and
[regression matrix](../../../docs/REGRESSION_MATRIX.md); they do not replace them.

From `app/`, with the [documented toolchain](../../README.md#development-checks):

```powershell
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoTestBuildType=debug -PcontakoCandidatePackageSuffix=candidate testDebugUnitTest --tests "com.patmanak.contako.compatibility.*"
```

Add `--offline` only if the complete verified dependency cache is available.
Use `-PcompatibilityProfile=deep` for the manual exploration profile, or
`-PcompatibilitySeed=42` to replay one seed. Supply
`'-PcompatibilitySource=<reviewed-commit>'` (append `-dirty` inside the quotes when applicable) to
record source provenance. Without it the trace explicitly says `UNRECORDED`;
the generated application version is recorded separately.

| Profile | Fixed seeds | Maximum commands per seed | Maximum contact slots | Execution budget |
| --- | --- | --- | --- | --- |
| quick (default) | 20 | 30 | 12 | 2 minutes per layer |
| deep (explicit) | 100 | 100 | 12 | 15 minutes per layer |

The version-1 generator uses a fixed causal command spine and a seeded Java RNG
for synthetic variants. The same version, seed and profile produce the same
commands and fault boundaries. Scheduling uses explicit commands, never sleeps.
The budget is a safety cutoff, not a performance assertion. Exhaustion fails
the test and records `NOT RUN`, rather than reporting incomplete exploration
as a pass. Reduction is limited to 200 replays or 60 seconds and retains the
initial case if no same-cause reduction can be established.

## Oracles and coverage

- Exact authored Unicode strings (including combining characters, `ß` and
  accented names), repeated email occurrences, distinct/shared per-email group
  IDs, multiline escaped notes, dates and partial addresses are checked against
  explicit expected values. Codec encode/decode round trips alone are not the
  oracle. An unrelated native phone or local note edit MUST preserve other
  values and opaque vCard text.
- Public/private input order and equivalent duplicate PREF parameters vary;
  invalid envelopes and blocked characters remain controlled rejections.
  Folded Unicode is checked independently at/below/above UTF-8 and logical-line
  limits. MIME row counts are checked at 127, 128 and 129.
- Provider observations retain synthetic opaque rows and BLOBs unchanged.
  Mixed raw-contact locators are rejected. The router test is not a database
  read or a write-receipt verification.
- The production mutation orchestrator drives test-store save, claim, external
  effect, interruption before acknowledgement, reopen and reconciliation.
  A reference state checks local values, revisions, pending intent and baselines
  after each command; simulated remote records and mapper projections are
  checked after convergence. A foreign-owner canary shares a logical contact
  ID and MUST remain unchanged. Two unchanged drains MUST add no writes.
- A deliberately faulty recovery double drops the reconciliation marker. The
  production orchestrator then exposes a duplicate CREATE, proving sensitivity
  of the oracle. The reducer replays the real orchestrator and accepts removal
  only when the same closed boundary/invariant signature recurs. Restoring the
  recovery contract MUST make the reduced sequence converge safely.

`build/qa-results/compatibility/` contains bounded local traces with generator
version, source, layer, seed, executed commands, result and failure boundary.
No contact values, exception messages, credentials or real identities are written.
`reduced-replay.txt` identifies the synthetic injected-defect case and reduced
commands; it is not evidence of an unfixed production bug.

## Evidence limits

These tests make no network calls and acquire no credentials or Android accounts.
The durable store and remote peer are controlled doubles; reopening them does
not qualify SQLite transactions, migrations or actual process death. Android
snapshots are mapper outputs, not ContactsProvider readback. Photo references
are synthetic retention canaries, not decoded media or compression receipts.
Cryptographic verification, physical OEM behavior, real Proton capabilities,
full sync-engine ordering, concurrent remote deletion/conflicts and runtime UI
remain separate integration/physical cases. No automatic CI schedule or random
production-account fuzzing is installed.
