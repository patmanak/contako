# Changelog

Features and improvements by version.

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
