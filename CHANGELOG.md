# Changelog

Features and improvements by version.

## 0.14.0

- Keep Android-copy recovery unresolved until deletion is proven, and retain the
  latest scoped failure cause across restarts with an appropriate action.
- Distinguish Android availability from Contacts permission during initial import,
  and keep known local validation instructions visible in synchronization issues.
- Add previewed, durable recovery requests for one rejected Proton card or one
  Android copy, processed by the existing account-scoped synchronization engine.
- Revalidate ownership, source generation, revision and provider context; retain
  retry delays and coalesce repeated requests without discarding existing intent.
- Capture native changes before retrying an Android copy, reuse guarded writes
  and receipts, and keep unrelated copies and Proton uploads outside that operation.
- Show queued, deferred, resolved and intervention results separately from
  whole-account synchronization; a missing shared prerequisite never starts a
  full repair or provider reset automatically.

## 0.13.0

- Give synchronization issues a scoped explanation and a suitable primary action:
  contact permission, same-account sign-in, version comparison, item review,
  eligible retry or local diagnostics.
- Keep independent contact, import, group and Android issues visible together;
  distinguish durable pending edits from changes not yet captured locally.
- Open local diagnostics directly from an issue without generating or exporting
  a report automatically; unidentified causes remain explicitly unidentified.

## 0.12.0

- Show a dismissible first-import overview and a persistent Sync summary, keeping
  already saved contacts accessible while other cards and Android copies wait.
- Rebuild availability and outstanding work from local records after restart;
  distinguish an empty completed inventory, partial import and missing Android
  permission without an estimated percentage or finish time.

## 0.11.0

- Add a deterministic compatibility bench for synthetic vCard variations, native
  edits and interrupted mutation sequences, with independent field expectations,
  bounded failure reduction and reproducible local traces.
- Keep simulated peer checks separate from Room, physical Android and Proton
  qualification; the bench never accesses an account or device.

## 0.10.2

- Keep verified contacts progressing when another remote card cannot be read;
  retain the rejected card as a durable, visible retry item.
- Preserve distinct Proton contacts sharing a vCard UID, local custom dates,
  proven local field labels and opaque phonetic-name properties.
- Handle changes to the preferred Android email without repeatedly replanning
  or transferring unrelated per-email group assignments.
- Bind retained local data to its Proton owner, allow same-account sign-in
  recovery without discarding pending edits, and import independently of Android provisioning.
- Retry unavailable group catalogs without freezing independent contact work;
  refresh cached capabilities after session changes and explicit refresh.
- Require targeted absence proof after an ambiguous remote deletion; distinguish
  authentication, storage, validation and Android projection failures.
- Bound vCard unfolding work and retain terminal diagnostic context after rotation.
- Preserve unmapped Android fields without blocking supported contact/photo edits;
  keep their opaque content in Android without uploading it to Proton.
- Recover photo rows recreated by native editors without Contako's identity/order
  markers, while retaining exact row ownership and write verification.
- Identify affected contacts and groups in synchronization errors, including pending
  deletions, and retain Android failure details across restart and other sync errors.
- Include a rotating, persistent local sync history in manually exported
  diagnostics: UTC/process-relative timings, request triggers, execution stages,
  known error/field categories, Android-copy backlog and retained failure context.
- Allow clearing local diagnostic history without removing exported documents;
  report history-storage failures without changing synchronization behavior.
- Keep generated technical reports expandable within the synchronization page.

## 0.10.1

- Preserve separate occurrences of identical email addresses and their individual
  Proton group assignments during Android edits and remote reconciliation.
- Recover eligible blocked group assignments from older repeated-email mappings
  without downloading every full contact during ordinary unchanged synchronization.
- Initialize Android group-membership baselines atomically and recover verified
  interrupted initialization while retaining ownership and write-receipt guards.
- Ignore aggregate photo-selection metadata when checking a source-owned Android
  photo, while retaining verification of the actual photo and guarded write receipt.
- Extend regression coverage for rich contacts, repeated fields, distinct and shared
  email groups, sequential native edits and linked contacts from another account.

## 0.10.0-RC1

- Record the actual Android photo representation after a guarded synchronous
  write, preserving the original during unrelated native edits and retaining
  explicit pending states for interrupted or unverified copies.
- Capture photos durably when a contact is created in the Android system editor,
  with photo-aware replay protection before acknowledging native changes.
- Keep incompatible contact data pending while continuing ingestion of unrelated
  contacts; retain account, ownership and storage failure guards.
- Reduce Android read-page size when the combined payload exceeds its byte
  budget, and verify owned raw-contact presence before skipping clean copies.
- Distinguish denied contact uploads from group-capability errors, including
  previously stored incorrect messages.
- Share a bounded bitmap-processing scope across Android photo loaders and
  verifiers to avoid multiplying decoded-photo allocations across workers.
- Preserve imported replacement characters and extend Unicode, native creation,
  partial-ingestion and projection regression coverage.

## 0.9.0-RC2

- Detect private-only Proton Web edits through the contacts event feed, with a
  durable account-scoped cursor and replay-safe checkpointing.
- Rebuild pinned Proton OpenPGP/SRP sources with Go 1.27.1, replacing the
  unsupported native runtime while preserving the retained public APIs.
- Add native source/provenance and checksum checks, retain embedded dependency
  notices, and expand private-change and native-build regression coverage.

## 0.9.0-RC1

- Hardened synchronization, concurrent-edit conflict handling and recovery.
- Added an in-app privacy-policy link and published data-handling documentation.
- Illustrated the README with English screenshots and fictional contacts, and
  added project-support and AI-assistance information.
- Expanded regression coverage for successive composite organization edits.

## 0.9.0

- Simplified contact editing with progressive field selection and compact field options.
- Made preserved read-only fields inspectable and clarified contact synchronization status.
- Improved account-menu alignment and long account names.
- Separated About and Usage into dedicated menu pages, with concise feature,
  privacy and licensing information and direct GitHub links.

## 0.8.0

- Improved synchronization recovery, interrupted work, safe sign-out and attention
  counters, with separate Proton-upload and Android-copy states.
- Improved preservation of imported contacts, repeated fields, unsigned encrypted
  cards, notes, dates and successive photo edits; retained opaque Samsung RCS data.
- Added denser directories, collapsed search, restored scroll position, explicit
  first/family names, compact contact actions and easier per-email group editing.
- Added date-picker presentation and bounded image selection; refreshed the
  launcher mascot and intertwined light/dark wordmarks.
- Hardened minified builds, protected authentication state and sanitized diagnostics;
  upgraded Proton libraries.
- Consolidated documentation and shared regression datasets.

Known limitations are listed separately in [the compatibility notes](docs/KNOWN_LIMITATIONS.md).

## 0.7.0

- Reduced unnecessary local contact writes and bounded incremental imports to
  limit repeated work when synchronizing large directories.
- Added resumable full repair, with persisted progress and network-aware execution.
- Grouped rapid edit triggers and bounded automatic synchronization scheduling,
  including checks at startup and when connectivity returns.

## 0.6.0

- Added eight-language presentation, light/dark/system themes, accessible and
  responsive navigation, and locale-aware alphabet scrolling.
- Improved contact details, synchronization recovery screens and application credits.

## 0.5.0

- Expanded advanced contact editing, dates, photos/logos and preservation of
  imported fields.
- Improved email-specific groups, group availability, deletion and primary fields.

## 0.4.0

- Connected the shared synchronization engine to Android's contact account.
- Added native contact/group edits and deletions, photo projection, account removal
  cleanup and recovery after provider resets.

## 0.3.0

- Added the durable synchronization foundation: queued local changes, serialized
  account operations and recovery after interruptions or uncertain remote writes.
- Added incremental contact/group reconciliation, conflict and deletion handling,
  and bounded retries with increasing delays.
- Persisted pending/action-required state and the last successful synchronization.
  Android production integration followed in 0.4.0.

## 0.2.0

- Added Proton sign-in and session management, including second-factor handling
  and protected session storage.
- Added Proton contact/group access and email-specific group assignments.
- Added contact encryption/decryption and vCard mapping, with preservation of
  imported fields and handling of uncertain remote writes.

## 0.1.0

- Established the local contact/group model, offline persistence and application foundation.
