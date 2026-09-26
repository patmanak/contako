# Licensing, provenance and distribution

Original Contako code remains **GPL-3.0-or-later**, copyright 2026 Patmanak.
The [root license](../LICENSE) retains that grant and the complete GPL version 3
text. Cleaning Git history does not change the license, remove third-party
attribution, or transfer asset ownership.

The exact runtime dependency inventory is
[app/legal/runtime-artifact-licenses.json](../app/legal/runtime-artifact-licenses.json).
[Third-party notices](../app/legal/THIRD_PARTY_NOTICES.md) and referenced full
license texts remain in app/legal. Preserve upstream copyright notices in source.
A third-party GPL-3.0-only entry is not a change to Contako's own grant.

The application includes Proton Core and other public libraries; distribution
must retain their applicable notices and provide corresponding source/build
instructions. Packaged assets/legal/NOTICE.txt links recipients to this repository
and its licensing materials. No prototype private file or signing key belongs here.

## Assets and identity

The existing octopus/application artwork is retained as project artwork created
for Contako. Proton names/logos identify interoperability and remain third-party
marks. Contako is independent and is not affiliated with, endorsed by or sponsored
by Proton AG. The standalone abandoned image study was not a packaged resource.
The [launcher source record](../app/design/launcher/README.md) preserves lossless
masters, creation method and checksums. Distribution MUST retain and review this
provenance against the actual packaged assets.

The packaged [asset manifest](../app/src/main/res/raw/asset_manifest.json) retains
runtime checksums and provenance. The shared September 24 mascot is generated
raster artwork with a recorded prompt; [its source record](../app/design/brand/README.md)
also preserves the previous owner-supplied wordmarks. The owner requested keeping
the octopus intertwined with the name and the "Contact for Proton" subtitle;
the new transparent light/dark wordmarks retain that composition. These generated
rasters have no layered/vector source and do not satisfy D-071's editable-master
requirement; this limitation is also listed in [Known limitations](KNOWN_LIMITATIONS.md).
The creator mark is owner-supplied. Neither those records nor the GPL grant claim
ownership of Proton's standalone trademark.

## Release preparation

Before distributing a build:

1. Validate the resolved dependency inventory and checksum metadata for that build.
2. Review dependency security findings with their real reachability limits.
3. Retain the corresponding source, build instructions, notices and artifact hashes.
4. Inspect actual packaged backup exclusions, debug/minification/logging flags.
5. Verify the intended package ID and signing certificate; keep keys private.
6. Enable GitHub private vulnerability reporting and verify the route documented
   in [the security policy](../SECURITY.md) before public distribution.

The application ID is com.patmanak.contako; test candidates may use an explicit
suffix. A debug-signed preview is suitable for dedicated testing, not a final
public signing identity. APK and store/F-Droid channels may have different
signatures; do not imply cross-channel update compatibility.

Public store publication, signing-channel choices and a release tag remain
separate from a clean initial Git commit. Never relabel old testing APKs as
artifacts built from rewritten source history.
