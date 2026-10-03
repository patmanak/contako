# Source-built Proton Android cryptography

Contako rebuilds Proton's unchanged GopenPGP v2.10.0-proton, go-crypto
v1.4.1-proton and go-srp v0.0.7 with **Go 1.27.1**.
The upstream android-golib 2.10.0-2 AAR embeds the unsupported Go 1.23.12 runtime.
The replacement is a Contako build, **not a Proton-published release**.
No cryptographic protocol or Proton source is patched.

`go.mod` and `go.sum` lock source inputs, including the maintained Go mobile
generator and its tooling dependencies. The cryptographic dependency versions
remain pinned; the generator selects golang.org/x/sys v0.48.0. These are
build/runtime inputs, not new product features.
The linked module/version/checksum inventory is embedded in the generated AAR.
The unused key-transparency JNI package is omitted: neither Contako nor the
selected Core source/API references it. This removes its unused certificate,
gRPC and transparency dependencies without changing the OpenPGP or SRP APIs.
Future Core upgrades MUST recheck that boundary.

## Build before running Gradle

Install the exact Go 1.27.1 toolchain from [Go](https://go.dev/dl/), Android SDK
with a platform at least API 31, Android NDK r27c (27.2.12479018), JDK 21,
PowerShell 7 and Python 3. Set the following environment variables to their
local installation directories. Run from the `app` directory:

```powershell
.\native\golib\build.ps1 -GoHome $env:GOROOT -AndroidSdk $env:ANDROID_HOME -AndroidNdk $env:ANDROID_NDK_HOME -JavaHome $env:JAVA_HOME
```

The build downloads checksum-verified pinned Go modules through the official Go
proxy and checksum database. It uses two Go workers, builds all four Android
ABIs, strips debug symbols and removes build paths. It checks every retained Proton
public Java declaration against the SHA-256-locked official 2.10.0-2 AAR, verifies the Go
runtime and Proton module versions in each native library, retains linked-module
license texts, normalizes archive timestamps and records source hashes.

Outputs are in ignored `app/native/build/maven/`. Gradle uses this repository
exclusively for `com.patmanak.contako.crypto:android-golib:2.10.0-2-go1.27.1`
and substitutes Core's transitive upstream golib request. It MUST NOT fall back
to the upstream binary. The Gradle pre-build guard checks the generated AAR/POM
against the reviewed verification metadata because local Maven files are otherwise trusted;
a mismatch MUST be investigated, not accepted by regenerating checksums blindly.
The initial binary qualification uses Windows x64/JBR 21/NDK r27c; other host
toolchains may require separately reviewed hashes and physical qualification.

For redistribution, include these build inputs with the corresponding source,
retain generated native notices and validate the actual minified APK. Public Java
API equality is necessary but MUST NOT replace cryptographic and device/Web tests.
Changing Go, NDK, generator, sources or packaging MUST produce a newly reviewed
artifact, runtime inventory and security assessment. No version match or native
rebuild establishes blanket freedom from vulnerabilities.
