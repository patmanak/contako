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
Python 3 and Bash on Linux. Set the following environment variables to their
absolute installation directories. The release reference is Linux; Windows
contributors can run the builder in WSL. Run from the `app` directory:

```sh
bash native/golib/build.sh --go-home "$GOROOT" --android-sdk "$ANDROID_HOME" --android-ndk "$ANDROID_NDK_HOME" --java-home "$JAVA_HOME"
```

The build downloads checksum-verified pinned Go modules through the official Go
proxy and checksum database. It uses two Go workers, builds all four Android
ABIs, strips debug symbols and removes build paths. It checks every retained Proton
public Java declaration against the SHA-256-locked official 2.10.0-2 AAR, verifies the Go
runtime and Proton module versions in each native library, retains linked-module
license texts, normalizes archive timestamps and records source hashes.

The NDK r27c external linker receives both `max-page-size=16384` and
`common-page-size=16384`, as required by the
[Android 16 KB guidance](https://developer.android.com/guide/practices/page-sizes).
The packaging guard parses each ABI's ELF headers and rejects a LOAD alignment
below 16 KB, an incorrect architecture, or RELRO sharing a trailing page with
writable data. These checks are separate from APK ZIP alignment; verify the
assembled APK with `zipalign -c -P 16 4` and run the cryptographic journey on a
physical 16 KB device before claiming runtime qualification for that page size.

Outputs are in ignored `app/native/build/maven/`. Gradle uses this repository
exclusively for `com.patmanak.contako.crypto:android-golib:2.10.0-2-go1.27.1-16k-linux1`
and substitutes Core's transitive upstream golib request. It MUST NOT fall back
to the upstream binary. The Gradle pre-build guard checks the generated AAR/POM
against the reviewed verification metadata because local Maven files are otherwise trusted;
a mismatch MUST be investigated, not accepted by regenerating checksums blindly.
The approved reference uses Linux x64/Go 1.27.1/JDK 21/NDK r27c. Two builds
MUST use distinct source and cache directories before approving a new artifact.
Windows and Linux NDK outputs are not assumed byte-identical. The historical
PowerShell builder remains available for investigation; its output MUST pass
the same approved checksums before use. Do not select a host-specific fallback.

### Windows development with WSL

Run the native builder in WSL with Linux Go/JDK/SDK/NDK paths. To use the result
from Windows Gradle, open the same checkout through its WSL-mounted path and
leave the default `--repository` location in that checkout. A Linux filesystem
work directory can keep intermediate compiler files separate:

```sh
# From the checkout's app directory in WSL, with Linux toolchain variables set:
bash native/golib/build.sh --go-home "$GOROOT" --android-sdk "$ANDROID_HOME" --android-ndk "$ANDROID_NDK_HOME" --java-home "$JAVA_HOME" --work "$HOME/.cache/contako-golib"
```

Alternatively, build in a separate Linux checkout and copy only its generated
`app/native/build/maven/com/patmanak/contako/crypto/android-golib/2.10.0-2-go1.27.1-16k-linux1/`
directory, including AAR and POM, into the matching path in the Windows checkout.
Windows Gradle retains the exact AAR/POM and canonical source-descriptor guards;
a stale or different result MUST fail. This supports Windows application
development, not Windows-native reconstruction or cross-host APK equality.
Public reproducibility comparisons use the complete Linux build described in
[F-Droid distribution](../../../docs/FDROID.md).

### Isolated native validation

For an isolated Linux review, `--work` and `--repository` select staging
directories without replacing Gradle's current local artifact. The PowerShell
equivalents are `-WorkDirectory` and `-MavenRepository`.
After reviewing the output, update the coordinate and exact checksum pins
together; neither a staging build nor successful packaging updates those pins.
The host ELF/RELRO regressions can run without the Android SDK:

```text
python native/golib/test-package-aar.py
```

On Linux, `python3 native/golib/test-build.py` checks rejection of unsupported
tool versions and malformed arguments before output directories are created.

For redistribution, include these build inputs with the corresponding source,
retain generated native notices and validate the actual minified APK. Public Java
API equality is necessary but MUST NOT replace cryptographic and device/Web tests.
Changing Go, NDK, generator, sources or packaging MUST produce a newly reviewed
artifact, runtime inventory and security assessment. No version match or native
rebuild establishes blanket freedom from vulnerabilities.
