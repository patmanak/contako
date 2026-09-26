# Launcher artwork sources

The September 24 shared transparent mascot supersedes the original foreground.
See [current artwork and prompt](../brand/README.md). The old runtime foreground
is preserved as `ast-003-previous-runtime.png`; the sources below remain historical.

These lossless masters are retained for future artwork maintenance. Runtime
resources under `app/src/main/res` are authoritative for the installed appearance;
this directory is not packaged into the APK.

The artwork was generated for Contako on 2026-08-01 with OpenAI's image-generation
tool from the owner's approved octopus/contact-book composition. A prototype
raster was used as a character reference, not copied into application resources.
The tool did not expose a backend model identifier, seed or editable vector.
The design removes the prototype padlock and contains no Proton logo.

| Source | Purpose | SHA-256 |
| --- | --- | --- |
| ast-003-launcher-opaque-master.png | Opaque square visual master | 89AA6B8CED513D1933D5C33252FE11FFAF708A92D012F825B6F3651BAD389A73 |
| ast-003-launcher-chroma-source.png | Flat green-background extraction source | 7B7664E8142881D0846ABF11F8493B4337651C8162BD6FE8BFD47C36FCD24F38 |

The original transparent derivative used border-key detection, a soft matte,
thresholds 12/220 and despill. Subsequent runtime sizing, theme and mask changes
are encoded in the current Android resources. Do not regenerate them from an old
layout assumption. Preserve adaptive safe areas and the themed monochrome icon.

The original brief called for a friendly purple octopus embracing a simplified
contact book, balanced tentacles, broad lavender planes, restrained gradients,
and generous safe space; no text, literal lock, Proton logo or glossy 3D treatment.
Store artwork export and final distribution provenance review remain release
work. Generated artwork is not a claim of exclusive trademark rights.
