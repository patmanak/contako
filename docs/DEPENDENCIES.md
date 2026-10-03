# Dependency policy

The checked-in [version catalog](../app/gradle/libs.versions.toml), Gradle
verification metadata and [runtime inventory](../app/legal/runtime-artifact-licenses.json)
define the build inputs. Dependency versions are technical inputs, not product
milestones. Full notices and source attribution MUST remain with distributed builds.

## Proton integration

The catalog pins Proton Core 36.8.0 and explicitly constrains
`com.patmanak.contako.crypto:android-golib:2.10.0-2-go1.27.1`, the
[source-built Proton bundle](../app/native/golib/README.md). Core's
[published crypto POM](https://repo.maven.apache.org/maven2/me/proton/core/crypto-android/36.8.0/crypto-android-36.8.0.pom)
requests golib 2.9.0-2; the build substitutes that request with the same
source-built OpenPGP/SRP artifact. Its exclusive local Maven repository MUST NOT
fall back to the upstream binary. The native selection is independent of the Core train.
An upgrade MUST preserve one aligned Core train, strict checksums and complete
runtime/legal inventory.

Use maintained contact, authentication, session, key and cryptographic primitives.
Custom protocol routes MUST remain narrow and replaceable. Review the exact
published source/API and resolved binary graph; matching source files alone does
not establish binary/resource equivalence. Rich HTTP error bodies exposed by Core
MUST NOT enter logs or diagnostic exports.

Private-change detection uses the contacts-only v6 routes defined in Proton's
[maintained WebClients event adapter](https://github.com/ProtonMail/WebClients/blob/main/packages/shared/lib/api/events.ts),
through the same authenticated Core client. The event reader handles pagination,
refresh requests and omitted empty collections. Public-directory fingerprints
remain lightweight index hints, never complete-card revisions. See
[Synchronization](SYNCHRONIZATION.md) for durable cursor advancement.

Qualify changed crypto/auth/contact paths on a physical runtime and affected
minified user journeys. A successful dependency resolution or JVM test MUST NOT
be reported as device/Web compatibility. Build commands are in
[app/README](../app/README.md); acceptance is in [QA](QA.md).

## Security interpretation

The upstream android-golib 2.10.0-2 artifact retains unsupported Go 1.23.12.
Contako rebuilds unchanged pinned Proton OpenPGP/SRP sources with Go 1.27.1,
checks the retained public JNI API against the official SHA-256-locked AAR and
verifies the runtime/module identity of all four native architectures. The unused
key-transparency JNI module and its unrelated dependencies are omitted; the
selected Core sources and Contako do not reference it. The recipe retains linked
module notices and source provenance. Future Core upgrades MUST recheck that boundary.

The [ASN.1 memory-exhaustion advisory](https://pkg.go.dev/vuln/GO-2025-4011)
affects the upstream runtime generation; a supported compiler rebuild addresses
that runtime version, not every Go-module advisory. Prior module-level advisory
matches MUST be reassessed against the generated artifact rather than assumed
closed. Stripped native metadata cannot prove function-level reachability or
blanket security clearance. The [Go release policy](https://go.dev/doc/devel/release)
and the exact embedded runtime remain maintenance inputs.

Previously reviewed Jackson/jsoup advisory matches were absent as class
definitions in a minified artifact. That observation does not cover debug builds,
changed keep rules or every future artifact. The exact APK and dependency graph
MUST be assessed together. AndroidX native components require their own coverage.

The contact path uses maintained Proton decrypt/verify/sign and SRP primitives.
The pinned golib streaming reader bounds accumulated decrypted contact bytes
before normalization; it is a direct build input as well as Core's constrained
runtime dependency. Complete native EOF/integrity and Core key fallback remain
required. Neither the application budget nor the 50 MiB native decompression
default guarantees peak process memory or covers every packet-parser allocation.
Do not weaken integrity or invent a crypto fork. See [Security](SECURITY.md).

## Reproducible assessment

The local audit tools MUST record artifact/inventory hashes, scanner identity,
database date and coverage. Distinguish package/version matches, reachable
attacker-controlled paths and missing evidence. Reports belong under ignored
app/build; public release notes contain only the relevant sanitized conclusions.
Do not copy historical advisory counts into a release decision.

- [Official govulncheck documentation](https://pkg.go.dev/golang.org/x/vuln/cmd/govulncheck)
- [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/)
- [Proton golib metadata](https://repo.maven.apache.org/maven2/me/proton/crypto/android-golib/maven-metadata.xml)
- [GopenPGP](https://github.com/ProtonMail/gopenpgp)
- [Proton go-crypto](https://github.com/ProtonMail/go-crypto)

See [licensing](LICENSING.md) for notices and distribution requirements.
