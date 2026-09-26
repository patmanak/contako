# Test result template

Copy this template under ignored app/build/qa-results/<local-run>/RESULT.md.
Keep private evidence/identifiers there; do not commit raw account/device data.
Replace placeholders with actual observations. Never prefill PASS.

## Identity and scope

- Date:
- Source commit and relevant uncommitted changes:
- Test level and exact command/class selection:
- APK variant/version/hash and installed readback (if applicable):
- Physical model / Android API / contact editor / locale / theme / font scale:
- Dedicated account confirmed (no address/credentials); Web available:
- Connectivity, contact permission and automatic-sync settings:
- Dataset version/commit, profiles and run-owned fixture aliases:
- Local-only evidence and identity-ledger location:

## Observations

| Case / regression / variant | Intended action and unchanged-field oracle | Contako observation | Proton Web observation | Android observation | Actual trigger / timing | Result |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

Results: PASS / FAIL / NOT RUN / OWNER-VALIDATED / KNOWN LIMITATION.
For unit/software-only runs, mark other peers not applicable rather than passed.

- Automatic success versus manual recovery:
- Pending Proton / pending Android / attention counts and matching list:
- No-change pass and restart result:
- Request/provider-write counts when required (not inferred from status):
- Image bytes/thumbnail, per-email groups and preserved-field comparisons:
- Failure boundary/retry/duplicate/resurrection observations:
- Unavailable variants, known limits and what the evidence cannot establish:

## Cleanup

- Exact run-owned scope removed; independent absence checks:
- Temporary packages/provider accounts removed:
- Intentionally retained fixtures and local ownership ledger:
- Existing sessions/unrelated data preserved:
- Public limitation or acceptance-contract changes, if any:
