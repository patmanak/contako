# Security and privacy

## Preserved boundaries

The local contact partition MUST retain a durable Proton owner independently of
revocable session records. A fresh login MUST NOT adopt another owner's retained
contacts or outbox. Same-owner reauthentication MUST preserve pending intent.
Only completed explicit local cleanup may release established ownership. An
initial login MAY reserve an empty partition before second-factor/key validation.
Abandoning that attempt MUST release only its provisional reservation after
proving that no local data or surviving session depends on it. Cancellation of
same-owner reauthentication MUST NOT release established ownership, even when
the current contact list is empty. Legacy nonempty
data without an owner or a retained session proving ownership MUST remain
quarantined; an incomplete legacy login MUST NOT claim a nonempty partition.
Missing evidence MUST NOT be interpreted as an empty partition.

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
- Ordinary and release builds disable Android Logcat diagnostics. `diagnostic`
  enables the reviewed typed Logcat sink; `syncDiagnostic` enables only closed
  sync enums and aggregates while remaining minified and non-debuggable.
- All variants maintain a local rotating journal of at most 128 closed technical
  events and a retained latest detailed failure (a generic result only when no
  finer cause was recorded in that pass). It records UTC
  wall time, elapsed milliseconds relative to the process, sync request origins,
  stages and known error/field categories. Authentication/cryptography traces,
  contact values, private IDs, URLs, raw messages and stack traces MUST NOT enter
  this journal. The retained failure may precede a later successful pass; its
  timestamp MUST NOT be treated as evidence of a current failure.
- The journal uses app-private, backup-excluded storage. Each atomic file and
  temporary replacement is capped at 96 KiB; writes are asynchronous and bursts
  are coalesced. Abrupt termination may lose the last unflushed events. A missing,
  corrupt or unwritable journal MUST NOT block sync or establish sync success.
  A persistence failure is visible in the report; a failed clear is reported to
  the user. Export validation is independent of collection and disk validation.
- Reports are generated and exported only on request; nothing is sent
  automatically. Users can clear local history separately from dismissing the
  displayed report. Neither action deletes documents already exported elsewhere.
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
