# Security and privacy

## Preserved boundaries

- Android sandbox/device security protects local contact data. The contact
  database is deliberately not encrypted separately at application level.
- Android backup is disabled and explicit rules exclude cloud, legacy full backup
  and device-to-device transfer.
- The canonical contact database MUST fail closed on detected SQLite corruption.
  Its open-helper callback MUST NOT delete and recreate the database; pending
  local intent remains on disk for recovery. This protection does not repair
  damaged data or guarantee recovery of every corruption or filesystem failure.
- Passwords, OTP/recovery values, session tokens, private keys, contact values and
  stable private identifiers MUST NOT enter logs, public evidence or source.
- Proton/Android protected storage and maintained Proton cryptography are used.
  Do not implement cryptographic protocols from scratch.
- Ordinary and release builds disable diagnostic logging. `diagnostic` enables
  the reviewed typed diagnostic sink; `syncDiagnostic` enables only closed sync
  enums and aggregates while remaining minified and non-debuggable. The manual
  in-app diagnostic summary is a separate, user-initiated export available in
  ordinary builds; it is not a raw log export or automatic telemetry.
- Release builds MUST be non-debuggable, minified and free of payload logging.

## Authentication and verification

Session invalidation rejects late callbacks from an earlier login/session generation.
One-password TOTP login may hold one mutable in-memory password copy, bound to the
session and a five-minute expiry. Consumption, cancellation, replacement and expiry
clear it. Two-password accounts still require their distinct mailbox password.

The human-verification bridge checks exact HTTPS origin, main frame, generation,
lifetime and message bounds using AndroidX WebKit. Unsupported providers receive
an update instruction and Back/cancel, without a frame-wide insecure fallback.
Same-origin verifier code remains trusted; the server validates the proof.

Authentication acceptance follows [QA](QA.md). Existing evidence does not
authorize access to its account or require a new login for unrelated work.

## Contact and network inputs

Reject malformed or invalidly signed cards. Owner-approved encrypted unsigned
imports remain supported; lack of a detached signature does not bypass decryption
integrity, field validation or bounds. Preserve supported unknown fields.

HTTP responses MUST be bounded before general conversion, closing resources on
failure. The interceptor covers maintained contacts/v4/contacts and core/v4/labels
routes, including decompressed/chunked bodies. Directory acquisition separately
bounds pages, totals, identities, group references and retained strings. The
Android photo projection checks dimensions before sampled bitmap allocation;
canonical photo bytes remain unchanged. See [Known limitations](KNOWN_LIMITATIONS.md).
This requirement MUST NOT be presented as completed universal coverage.
Do not fetch arbitrary contact-controlled image URLs during sync. Validate and
bound selected/inline image decoding, including orientation and metadata removal.

Encrypted contact cards use Proton's maintained streaming reader. Accumulated
decompressed bytes MUST stay within the remaining 10 MiB contact budget before
text normalization and parsing. A limit failure MUST reject the entire card;
only a genuine native EOF completes the integrity check. Detached verification
and Core's private-key fallback remain required. Cancellation is cooperative
between reads and MUST NOT permit a partial result or an unbounded fallback.
This bound is stricter than a limit applied only after CRLF normalization. It
does not bound every native allocation or guarantee a process-memory ceiling;
upstream decompression also has a 50 MiB default cap. Bounded input
handling MUST preserve maintained key fallback and complete integrity checks.
See [Dependencies](DEPENDENCIES.md) and [Known limitations](KNOWN_LIMITATIONS.md).

## Test and disclosure practice

Report suspected vulnerabilities through the private channel described in
[the security reporting policy](../SECURITY.md).

Use controlled fixtures in an explicitly authorized test environment. Establish
the current device/account scope before operations; do not infer authority from
public test plans. Contact-testing permission does not authorize password,
recovery, security-key, billing, subscription, 2FA-setting or account-deletion
changes. Local operator restrictions remain private.

Keep screenshots, raw device output, databases, credentials, serials and private
paths out of commits. Local recovery archives MUST remain ignored.
See [QA](QA.md) and [known limitations](KNOWN_LIMITATIONS.md). No security audit here is
a claim of exploitability clearance or penetration-test completion.
