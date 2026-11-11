# Build Contako for Android

Run Gradle commands below from this directory. Android Studio provides the JBR
and Android SDK; set JAVA_HOME and ANDROID_HOME, or use an untracked
local.properties for sdk.dir. Do not commit local configuration or signing keys.

## Toolchain and variants

Application numbering and tag preparation follow
[Versioning and tags](../docs/DEVELOPMENT.md#versioning-and-tags).
The base version and Android versionCode live in `build.gradle.kts`; About and
diagnostic displays derive from generated BuildConfig values.

The wrapper, version catalog and dependency-verification metadata are authoritative.
Current descriptors use Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10, compile/target SDK 36
and min SDK 31. Java/Kotlin bytecode targets 17. The existing qualification used
Android Studio JBR 21.0.10; the bytecode target is not the host runtime version.

| Variant | Purpose |
| --- | --- |
| debug | Debuggable development; ordinary payload logging remains disabled |
| release | Non-debuggable, minified and resource-shrunk; unsigned APK without release signing configuration |
| preview | Release-derived, minified, debug-signed local test build; not a public release |
| diagnostic | Debug-derived, explicitly enables the reviewed sanitized diagnostic sink |
| syncDiagnostic | Preview-derived, minified, non-debuggable and debug-signed; only closed sync diagnostics enabled |
| investigation | Release-derived, minified and non-debuggable; closed sync/import tracing enabled, unsigned without release signing configuration |
| benchmark | Release-derived instrumentation/performance variant |

The default instrumentation test build type is benchmark. The commands below
explicitly select debug so that JVM and device test task selection is unambiguous.
A candidate suffix isolates the package/account type from a main-phone installation.
`investigation` keeps the normal package ID unless `contakoCandidatePackageSuffix`
is explicitly set. Use a candidate for isolated tests; an in-place diagnostic
update needs the existing signing identity and authorization to update that installation.

## Development checks

Before the first Gradle build, generate the checksum-locked native dependency
using [the Proton source-rebuild instructions](native/golib/README.md).
This requires Go 1.27.1 and NDK r27c in addition to the Android/JDK toolchain.
Release native artifacts are rebuilt on Linux (including WSL), with the same
strict verification for subsequent Gradle builds on any host.
Gradle MUST fail if this local artifact is absent or its checksum differs.

```powershell
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoTestBuildType=debug -PcontakoCandidatePackageSuffix=candidate testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoTestBuildType=debug -PcontakoCandidatePackageSuffix=candidate assembleDebug assembleDebugAndroidTest
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoTestBuildType=debug -PcontakoCandidatePackageSuffix=candidate lintDebug
```

Keep lint in its own invocation. Add --offline only when the verified cache is
complete; never disable dependency verification to make a build pass.
The legacy contakoJvmCheck task selects benchmark unit tests; it is not an alias
for the explicit debug command above.

For a small ARM64 test APK:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 -PcontakoCandidatePackageSuffix=candidate -PcontakoTargetAbi=arm64-v8a assemblePreview
```

Expected output: build/outputs/apk/preview/Contako-preview.apk.
Without contakoTargetAbi, the normal ABI configuration is used.
For distributable unsigned artifacts use assembleRelease and bundleRelease,
then complete the signing and source-provenance checks in [Licensing](../docs/LICENSING.md).
Changing package ID or signing certificate affects upgrade compatibility.
An APK copied into an owner-testing directory is not the authoritative build
output and MUST NOT be treated as publicly signed merely because its filename
contains `release`. Record installed artifact identity and tested scope in the ignored
[result template](../docs/testing/RESULT_TEMPLATE.md).

Proton Core is pinned to 36.8.0. A separate strict constraint selects the
Contako source rebuild of android-golib with Go 1.27.1; Core's transitive
upstream binary request is substituted with that same local artifact.
The current rebuild validates 16 KiB ELF LOAD alignment and compatible RELRO on
all four ABIs. APK ZIP alignment is a separate packaging check; actual 16 KiB
runtime qualification requires a suitable device.
See [Dependencies](../docs/DEPENDENCIES.md) for checksum, license and validation
evidence; a version update alone does not close security findings.

## Physical testing

Use a physical phone for actual OEM/provider acceptance. Follow local operator
instructions for permitted devices, emulators, debuggers and resource limits.
Select the connected device at execution time and keep its identifier private.
Install with adb install -r when package/signature compatibility permits.
Do not uninstall or reset an authenticated installation merely to run contact tests.

Run only the explicitly selected instrumentation classes. Several live probes
under src/androidTest require opt-in arguments and an existing dedicated session.
Do not run the entire instrumented suite blindly: some tests change account or
contact state. The [QA guide](../docs/QA.md) describes retained test categories;
the [functional plan](../docs/FUNCTIONAL_TEST_PLAN.md) defines independent phone/Web oracles.

`qa/check-security-boundaries.ps1` requires an explicit `-DeviceSerial` and API 35.
It uses the isolated `securitycandidate` package, refuses pre-existing fixtures
and removes only installations created successfully by that run. Its backup
checks clear synthetic candidate data; they MUST NOT target an authenticated
installation. `qa/test-security-boundaries.ps1` checks these guards without a device.

## Dependency and release tools

The QA scripts are documented in [QA](../docs/QA.md). The notices generator below
is cache-only and invokes Gradle offline. First complete the normal verified
dependency resolution, including required parent POMs and
the source-built native artifact; a fresh empty cache cannot run this step.

```powershell
$reviewedHead = git rev-parse HEAD
powershell -NoProfile -File qa/generate-runtime-notices.ps1 -ExpectedHead $reviewedHead -ValidateOnly
```

Use -StageOnly for reviewable replacement notices and -AuditOnly for read-only
completeness review. A resolved dependency change needs inventory regeneration;
a documentation/history change alone does not justify changing legal entries.

The native audit uses Python 3.11+ and reviewed official Go/govulncheck executables:

```text
python qa/audit-runtime-security.py --apk <apk> --manifest legal/runtime-artifact-licenses.json --expected-apk-sha256 <sha256> --expected-manifest-sha256 <sha256> --go <go-executable> --govulncheck <govulncheck-executable> --output build/security-audit-<unique-run>
```

First establish that the inventory belongs to the APK. Hashes alone do not prove
build provenance. A completed scan is not a vulnerability clearance; stripped
native binaries may yield only module-level findings. See [Dependency review](../docs/DEPENDENCIES.md).
