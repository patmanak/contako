# Target specification

Contako's target is one Android application for managing Proton contacts and
synchronizing them with the system contact account. The specification describes
the intended product, without intermediate release milestones.

MUST, MUST NOT, SHOULD and MAY express requirement strength as in RFC 2119.
A requirement is not evidence that every implementation or device case passes.
[Known limitations](KNOWN_LIMITATIONS.md) distinguish current gaps from the target;
[the changelog](../CHANGELOG.md) records user-facing changes by version.

| Contract | Authoritative document |
| --- | --- |
| Features, supported platform, exclusions and performance targets | [Product](PRODUCT.md) |
| Data ownership, persistence, boundaries and recovery transactions | [Architecture](ARCHITECTURE.md) |
| Sync ordering, conflict resolution, scheduling and interoperability | [Synchronization](SYNCHRONIZATION.md) |
| Runner, mutation, capability, authentication and repair transitions | [State machines](SYNC_STATES.md) |
| Contact fields, cardinality, wire mapping and lossless preservation | [Contact mapping](CONTACTS.md) |
| Navigation, forms, themes, images and accessibility | [Interface](DESIGN.md) |
| Authentication, storage, untrusted inputs and diagnostics | [Security](SECURITY.md) |
| Maintained dependencies and artifact-specific review | [Dependencies](DEPENDENCIES.md) |
| Licensing, artwork provenance and distribution identity | [Licensing](LICENSING.md) |

[Decisions](DECISIONS.md) retains identifiers referenced by implementation and
tests. They identify durable product choices, not development tasks or milestones.
Detailed contracts above govern their domain; a proposed feature MUST NOT become
a requirement merely by appearing in a work note.

Acceptance is defined separately in [QA](QA.md): unit, software integration and
physical target/Proton tests share synthetic datasets and regression coverage.
Execution logs, agent handoffs, build-install transcripts and temporary task lists
MUST remain outside the public specification.
