# F-Droid distribution

Contako is not yet included in F-Droid. The [draft recipe](../fdroid/metadata/com.patmanak.contako.yml)
is deliberately disabled at both application and build level. Linux native
reconstruction and APK signature reproduction have been qualified locally.
The F-Droid build-server run, review of the source-scan exception and a matching
published APK remain required before enabling it or requesting inclusion.

The native release reference is Linux. Its AAR and canonical LF POM are verified
across clean builds with distinct source and cache directories, with exact
checksums, API/module identity and four-ABI ELF checks. Windows-native outputs
are not assumed identical. Use the [Linux builder](../app/native/golib/README.md),
including through WSL, rather than selecting a host-specific binary fallback.

## Listing and source

[Fastlane metadata](../fastlane/README.md) supplies English and French text,
the existing application icon and English screenshots with fictional contacts.
Maintain changelogs by Android versionCode alongside the central version.
The draft targets `v1.0.1`, whose source contains these listing files. Publish
that tag only on the reviewed, qualified commit. Its `Binaries` URL expects the
matching developer-signed universal APK on GitHub; verify the tag and asset
before enabling the recipe. Do not copy the Fastlane
directory into fdroiddata: its localized text filenames differ and screenshots
are not accepted there. Update the source revision and published APK together.

The proposed `TetheredNet` label describes the dependency on the Proton service.
F-Droid maintainers determine the final classification and inclusion outcome.
See their [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/).

## Linux build recipe

Use a dedicated checkout of [fdroiddata](https://gitlab.com/fdroid/fdroiddata)
and the official [F-Droid build environment](https://f-droid.org/docs/Build_Server_Setup/).
Install fdroidserver according to its upstream instructions, then copy the
draft YAML to `fdroiddata/metadata/`. In that disposable checkout only, remove
the two `Disabled`/`disable` entries so lint actually checks the candidate
instead of skipping a disabled app. Run there:

```sh
fdroid readmeta
fdroid lint com.patmanak.contako
fdroid build --server --verbose com.patmanak.contako:31
```

Metadata checks do not qualify a native build. Keep the tracked draft disabled
until the complete build and comparison below pass. Review source-scanner
findings individually; do not add blanket `scanignore` exclusions. The draft
exempts only `app/settings.gradle.kts`: the scanner reports its group-exclusive
local Maven directory, populated from Proton source during `build`, as unknown.
An exact canonical SHA-256 check in `prebuild` prevents that exception from
silently covering changed repository declarations. Dependency declarations and
all binaries remain scanned. Include this rationale in the submission for
maintainer review, as required by the
[scanignore guidance](https://f-droid.org/docs/Build_Metadata_Reference/#scanignore).

The recipe builds pinned Go 1.27.1 from the F-Droid Go srclib, then builds the
locked Proton cryptography modules using NDK r27c, SDK 36 and JDK 21. Its host
bootstrap Go MUST be at least 1.24.6; the recipe rejects an older distro package.
Adjust the build image/bootstrap provision explicitly if necessary. The native
compilation runs in `build`, after source scanning, rather than `prebuild`.
After bootstrapping Go, the recipe invokes the versioned
[native builder](../app/native/golib/README.md) from the selected source commit.

The official upstream AAR is downloaded with a fixed SHA-256 solely to compare
the public Java API. Packaged JNI libraries MUST come from the source build.
The existing packager checks API equality, module/runtime identity, all four
ABIs, licenses and 16 KiB ELF layout. Gradle still enforces its exact native
AAR/POM checksums and source provenance. A Linux hash mismatch MUST stop the
build; investigate it before reviewing any change to the approved checksums.
Do not regenerate verification metadata merely to make this recipe pass.

## Signing and reproducibility

The proposed route preserves updates between GitHub and F-Droid installations:
F-Droid rebuilds the APK and verifies the published developer-signed binary.
`Binaries` selects the versioned GitHub APK; `AllowedAPKSigningKeys` pins the
existing public certificate SHA-256:

```text
2620844332bce89ff471d0c64629f25fe3fabd1d465d01780f7649f4d023f4fb
```

No private signing key is supplied to F-Droid. Independently verify that
certificate on the downloaded release with Android Build Tools `apksigner
verify --verbose --print-certs`. Using a signed published APK and the independently
rebuilt **unsigned** universal APK, reproduce the padding applied by the release
signer before comparison. Android Build Tools 35+ changes this ZIP padding even
when the input is already aligned. The recipe uses the pinned F-Droid
[reproducible-apk-tools](https://gitlab.com/fdroid/reproducible-apk-tools)
srclib in `postbuild`; for a manual comparison, from the repository root:

```sh
git clone https://gitlab.com/fdroid/reproducible-apk-tools.git app/build/reproducible-apk-tools
git -C app/build/reproducible-apk-tools checkout ca728486d42a79f1c9ec0c6ec755c39f251911a8
python3 app/build/reproducible-apk-tools/zipalign.py --page-size 16 --pad-like-apksigner --replace rebuilt-unsigned.apk rebuilt-aligned.apk
python3 fdroid/verify-reproducible.py published.apk rebuilt-aligned.apk
```

Never realign the signed published APK. This normalization changes ZIP padding,
not contact data or application payloads; the final signature comparison remains
mandatory. The [upstream explanation](https://github.com/obfusk/apksigcopier#what-about-signatures-made-by-apksigner-from-build-tools--3500-rc1)
describes this signing-tool compatibility issue.

This requires `apksigner` on PATH and the
[apksigcopier tool referenced by F-Droid](https://f-droid.org/docs/Reproducible_Builds/).
The helper pins the expected signer and rejects identical inputs, signed rebuilds
and comparison-environment overrides. A pass proves comparison of those APKs;
the operator MUST separately retain the reviewed source commit, clean Linux build
environment and build logs. Comparing two artifacts from the same build does not
prove independent reconstruction. Final comparisons MUST use clean Git checkouts
at the selected commit: Gradle embeds the revision in
`META-INF/version-control-info.textproto`, so source-archive builds without Git
are not equivalent to the F-Droid checkout. Preserve that metadata rather than
discarding it during comparison. Native identity and APK byte equality are
separate gates. If reproducibility cannot be achieved, F-Droid signing is a
different distribution choice that MUST be decided before submission.

Run the helper's device-free rejection-path tests from the repository root:

```sh
python3 -m unittest discover -s fdroid -p 'test_*.py'
```

These tests do not qualify an independently built APK.

## Submission and updates

After qualification, review the complete recipe, listings, licenses and dependency
scan, remove both disable flags and follow the
[submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
to request inclusion or open a fdroiddata merge request. Preparation alone does
not publish anything.

Automatic builds remain off (`AutoUpdateMode: None`). The draft's update check
matches stable `vX.Y.Z` tags and extracts the central version and Android code
from `app/build.gradle.kts`; legacy and RC tags are excluded. After qualification,
consider `AutoUpdateMode: Version` with F-Droid maintainers; tag checks already
select the matching `vX.Y.Z` tag. Each new build
MUST reference reviewed source, matching GitHub assets and updated listing notes.
See [versioning and tags](DEVELOPMENT.md#versioning-and-tags) and the
[metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/).
