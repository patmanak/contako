# Store listing metadata

`metadata/android/` follows the [F-Droid-supported Fastlane structure](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/).
No Fastlane executable or store account is needed to maintain these files.
English (`en-US`) is the fallback listing; French (`fr-FR`) supplies translated text.
The application itself supports the languages listed in the product documentation.

## Text and version updates

- Keep `title.txt` within 50 characters and `short_description.txt` within 80,
  without a trailing full stop in the short description.
- Keep `full_description.txt` within 4,000 characters. Descriptions MUST match
  available behavior and MUST NOT claim F-Droid acceptance or reproducible builds.
- Name each changelog after the exact Android `versionCode`, with no padding:
  `30.txt` describes version 1.0.0 and `31.txt` version 1.0.1.
  Each entry MUST stay within 500 characters.
  Preserve older entries and add the next code when preparing another release.
- Keep translations consistent. English screenshots MUST NOT be represented as
  captures of a French interface.

F-Droid reads these files from the release revision selected by its build metadata.
Unreleased listing changes are not automatically part of an older tag.

## Image provenance

Images under `en-US/images/` are byte-for-byte copies of existing public assets:

| Listing file | Authoritative source | Content |
| --- | --- | --- |
| `icon.png` | [Current mascot](../app/src/main/res/drawable-nodpi/contako_mascot_v2.png) | Existing transparent square Contako artwork |
| `phoneScreenshots/1.png` | [Login capture](../docs/images/login.png) | Empty sign-in form |
| `phoneScreenshots/2.png` | [Directory capture](../docs/images/contacts.png) | English interface with fictional contacts |
| `phoneScreenshots/3.png` | [Contact capture](../docs/images/contact.png) | English interface with a fictional rich contact |

The [artwork source record](../app/design/brand/README.md) and
[screenshot record](../docs/images/README.md) retain provenance and licensing.
The icon is the unmodified 1254 × 1254 RGBA master; it is not a new adaptive-icon
render. The screenshots illustrate the interface, not live synchronization or
F-Droid qualification. They are English only; French listing text does not imply
French screenshots.

Future replacement images MUST be reviewed for personal data and retain their
source/provenance. Do not reuse contact/account diagnostics as promotional images.
