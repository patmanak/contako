# Dependency policy

The checked-in [version catalog](../app/gradle/libs.versions.toml), Gradle
verification metadata and [runtime inventory](../app/legal/runtime-artifact-licenses.json)
define the build inputs. Dependency versions are technical inputs, not product
milestones. Full notices and source attribution MUST remain with distributed builds.

## Proton integration

The catalog pins Proton Core 36.8.0 and explicitly constrains
`me.proton.crypto:android-golib:2.10.0-2`. Core's
[published crypto POM](https://repo.maven.apache.org/maven2/me/proton/core/crypto-android/36.8.0/crypto-android-36.8.0.pom)
requests golib 2.9.0-2, so the native selection is independent of the Core train.
An upgrade MUST preserve one aligned Core train, strict checksums and complete
runtime/legal inventory.

Use maintained contact, authentication, session, key and cryptographic primitives.
Custom protocol routes MUST remain narrow and replaceable. Review the exact
published source/API and resolved binary graph; matching source files alone does
not establish binary/resource equivalence. Rich HTTP error bodies exposed by Core
MUST NOT enter logs or diagnostic exports.

Qualify changed crypto/auth/contact paths on a physical runtime and affected
minified user journeys. A successful dependency resolution or JVM test MUST NOT
be reported as device/Web compatibility. Build commands are in
[app/README](../app/README.md); acceptance is in [QA](QA.md).

## Security interpretation

The selected native artifact retains Go 1.23.12. Prior module-level advisory
matches are not automatically closed by the golib upgrade, nor do they establish
exploitable paths in the packaged application. Stripped native metadata cannot
prove function-level reachability or clearance.

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
