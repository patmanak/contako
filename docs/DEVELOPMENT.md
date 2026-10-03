# Development workflow

Start with [Specification](SPECIFICATION.md), [Product](PRODUCT.md) and the affected
[synchronization](SYNCHRONIZATION.md)/[field](CONTACTS.md) contracts.
Check Git status and preserve unrelated work. Source and public documentation are
English. Private operator preferences are loaded through the root AGENTS.md;
see [Agent setup](AGENT_SETUP.md). Do not duplicate them in public instructions.
Use [the agent working method](WORKFLOW.md) when splitting a substantial task
between specialized roles; ordinary edits can stay with the primary agent.

## Make one reviewable change

Implement Android changes inside app/. Keep canonical ownership, durable intent,
one account-scoped sync engine and unknown-field preservation intact.
Use maintained Proton primitives and narrow replaceable protocol adapters.
Do not combine cleanup with speculative dependency upgrades or behavior changes.

Before changing a contract, identify an existing decision or record the unresolved
choice in [Decisions](DECISIONS.md). Implementation choices within approved
behavior do not require repeated owner confirmation. User authorization and
current scope take precedence over obsolete campaign procedure.

## Verify the behavior that changed

Before a first Gradle build, follow the [Android build prerequisites](../app/README.md#development-checks)
and generate the [source-built Proton cryptography bundle](../app/native/golib/README.md).
Gradle rejects missing, stale or checksum-mismatched native artifacts.

Use [QA](QA.md) to select unit, software-integration and physical target cases.
Shared synthetic data and the regression matrix replace historical campaign
selection. Record each run locally with the result template, not in the plan.

For a sync/UX defect, reproduce the relevant action on the physical phone and
Proton Web using owned test contacts. Verify the requested result independently
in the other peer and the system contact app. Use a small focused deterministic
check when it protects the defect; do not substitute fake-only campaign results
for real behavior.

Run affected JVM checks, instrumentation compilation, separate lint and minified
checks as appropriate. Use environments allowed by the current operator;
an emulator does not qualify real OEM/provider behavior. A documentation
cleanup does not require another login or destructive live run; demonstrate
unchanged production inputs and verify local documentation links and referenced
commands against the build descriptors. Compile retained tests when test files
or build inputs actually change; prose-only edits do not require an APK rebuild.

Record a compact result using [QA](QA.md): source/build identity, relevant command,
device scope, pass/fail and cleanup. Keep known limitations and the functional plan
consistent with observed behavior. Do not accumulate chronological handoff novels or one-shot runners.

The README and [user guide](USER_GUIDE.md) explain the implementation.
[Specifications](SPECIFICATION.md) define the target; [known limitations](KNOWN_LIMITATIONS.md)
describe deviations. [The changelog](../CHANGELOG.md) summarizes delivered user-facing
changes by version. Keep temporary progress, handoffs, audit reports and task lists
ignored and local. Do not create a parallel public agent work log.

## Versioning and tags

The application version has one source of truth. A request to change its number
updates source metadata and documentation; building, installing, committing,
tagging and publishing are separate actions governed by the requested scope.

### Version change

| Location or consumer | Required action |
| --- | --- |
| `app/build.gradle.kts`: `contakoReleaseVersion` | Set the requested `X.Y.Z` application version. This also supplies `defaultConfig.versionName` and `BuildConfig.PROTON_RELEASE_VERSION`. |
| Same file: `defaultConfig.versionCode` | Increase the integer beyond previously distributed builds on the intended upgrade path. Do not derive it by dropping dots or reset it for a new major version. |
| Same file: `versionNameSuffix` per build type | Preserve the variant suffixes. A base-version bump does not turn a preview or diagnostic build into a release. |
| `ContakoApp.kt`: About page | Reads `BuildConfig.VERSION_NAME` through the localized `about_version` format. Do not edit the UI or eight string catalogs merely to change a version. Settings contain no independent version number. |
| `DiagnosticSettings.kt`: exported report | Reads `BuildConfig.VERSION_NAME`, including the variant suffix. Check applicable validator limitations; do not disguise the build by stripping its suffix. |
| `ProtonGateCNetwork.kt`: client identity | Reads `BuildConfig.PROTON_RELEASE_VERSION`, without the Android variant suffix. Keep client identity and its validator derived from the same value. |
| Root `CHANGELOG.md` | Add a concise section for the version, describing delivered user-facing changes. Retain older sections; do not claim unfinished fixes or unexecuted qualification. |
| `README.md`, `app/README.md` and `docs/` | Update current-version/download examples if present, and user guide, specification, limitations or test acceptance when behavior changes. Avoid repeating a current-version number where a link to the changelog suffices. |
| Generated BuildConfig, merged manifest, APK/AAB metadata, SBOM and copied APKs | Regenerate from the intended source when needed. Never hand-edit generated files or rename an old APK to imply a new version. `app/qa/check-release.ps1` derives the SBOM application version from the Gradle declaration. |

Inspect tracked references to the previous version, but distinguish the application
version from dependency versions, historical changelog entries, artwork provenance
and fixed protocol/test examples. Do not perform a global replacement. A product
version change does not change the package/account type, signing identity, Room
schema version, SDK levels or Proton dependency versions.

For a metadata-only change, run from `app/` with the toolchain described in
[the build guide](../app/README.md):

```powershell
.\gradlew.bat --no-daemon --max-workers=2 :generateReleaseBuildConfig :processReleaseMainManifest
```

Add `--offline` only with a complete verified dependency cache. Inspect the
generated release `BuildConfig.java` and merged `AndroidManifest.xml` under
`app/build/`: `VERSION_NAME` and `PROTON_RELEASE_VERSION` MUST match the requested
base version, and `VERSION_CODE`/manifest `versionCode` MUST match the new integer.
This checks metadata, not an assembled APK or runtime behavior. Document the
scope in the ignored [result template](testing/RESULT_TEMPLATE.md).

If an APK or installation is also requested, build the intended variant and
verify its packaged versionName/versionCode, suffix, package, signature and hash.
For an installation, confirm the installed artifact and About display while
preserving the existing session. Select further validation by the actual changes,
using [QA](QA.md); a version-only edit does not require resetting accounts or
replaying the full contact campaign.

### Tag and publication

1. When tagging is requested, identify the exact reviewed commit. It MUST contain
   the intended version, versionCode and matching changelog. Uncommitted changes
   are not part of a tag; preserve unrelated work and commit the release changes
   within the authorized scope before tagging.
2. Use an annotated `vX.Y.Z` tag whose number matches the base Gradle version.
   Confirm whether that name already exists. An existing tag at another commit
   MUST NOT be replaced or deleted implicitly; an existing matching tag needs no
   recreation. Record the resolved target, not just the branch name.
3. Create the local tag only on that commit, then verify its annotation and peeled
   commit ID. A tag marks source; it does not build, sign, install or publish it.
4. Before public distribution, follow [Licensing](LICENSING.md),
   [QA](QA.md) and [history maintenance](REPOSITORY_RESET.md). Review the tree and
   all history reachable from the proposed tag, including author metadata and
   deleted files. A clean tip or `.gitignore` does not sanitize old history.
5. Push only explicitly authorized references. Publishing a GitHub release or
   uploading assets is a separate authorized operation; do not use a blanket
   tag/mirror push. Build artifacts from the tagged source, retain signing and
   hash provenance, and publish concise notes consistent with the changelog and
   known limitations. No build from retired history may be relabeled as a clean
   public release.

## Targeted synchronization diagnostics

The `syncDiagnostic` variant inherits the minified, non-debuggable, debug-signed
`preview` configuration and uses the `-sync-diagnostic` version suffix. Its
`SYNC_DIAGNOSTICS` flag enables only closed synchronization enums and aggregate
status counters in the `ContakoDiagnostic` Android log tag. Authentication and
cryptographic traces remain disabled. Ordinary debug/preview/release builds keep
both diagnostic flags disabled. This is an operator artifact, not a public release.

Build from `app/` using the local JDK and cached dependencies:

```powershell
.\gradlew.bat --offline --no-daemon --max-workers=2 -PcontakoTestBuildType=debug -PcontakoCandidatePackageSuffix=candidate -PcontakoTargetAbi=arm64-v8a :assembleSyncDiagnostic
```

Read `SYNC_STAGE`, `SYNC_EXCEPTION`, `SYNC_OUTCOME` and `ANDROID_STAGE` together.
Projection repair/replan and ingestion categories explain deferred Android work;
`PROJECTION_COMPONENT` identifies the first unequal component after the same
generated-name, primary-flag and empty-value equivalences used by fingerprint
verification. It carries only row kind, component name and a closed difference
class (expected empty, observed empty, whitespace only, different). These classes
MUST NOT relax verification or be treated as proof of an intended user edit.
`MUTATION_FAILURE` identifies an action-required Proton outbox boundary. These
events MUST NOT carry contact identities, values, exception text or stack traces.
`CONTACT_UPDATE_FAILURE` distinguishes update validation, encoding, protection,
prepared-card verification, the remote update call and confirmation readback.
It carries only a closed stage/category and, when established locally, an
encoding stage and field kind. `REMOTE_UPDATE` identifies the port boundary,
not necessarily a server HTTP response. Null detail means unavailable; it MUST
NOT be guessed from the edited field. These diagnostics do not retry an update,
change failure classification, suppress cancellation or clear pending intent.
`CONTACT_ERROR` hydration values `VCARD_PARSE_*` refine local parser refusal into
bounds, blocked characters, envelope, duplicate labels, duplicate PREF/VALUE
parameters, parameter quoting, duplicate cards and public-key validation. These
closed reasons retain MALFORMED_RESPONSE and carry no rejected text, actual
parameter name/value, exception message/cause or contact identity. An unchanged
generic VCARD_PARSE is still possible for an unclassified rejection. Diagnostic
refinement alone MUST NOT change acceptance or encoding failure policy. Under
the separate F-15 compatibility correction, equivalent valid singleton repetitions
are accepted; VCARD_PARSE_DUPLICATE_PARAMETER then denotes conflicting/invalid
repetitions. See Contacts for the preservation and output-normalization contract.
`PHOTO_WRITE_FAILURE` refines the existing `PHOTO_WRITE` repair aggregate: source
unavailable/empty/oversized, post-stream ownership, or post-bind ownership/dirty/
value binding. `INGEST_PLAN_FAILURE` exposes the existing closed planning reason;
if it is `ROW_CODEC_FAILURE`, `INGEST_ROW_FAILURE` supplies the closed decoder
category. Detail events MUST NOT increment repair counts or change retry/commit
decisions. Observer failures remain isolated from synchronization.
Missing initial contact-baseline diagnostics distinguish absence of a photo
recovery path/journal, incompatible recovery state, failed photo readback proof,
different managed contact values, and different memberships. These categories
refine the former `CONTACT_BASELINE_MISSING` result without relaxing recovery.
`PROJECTION_REPLAN=CURRENT_OBSERVATION_DIRTY` identifies a native edit awaiting
durable ingestion; `CURRENT_OBSERVATION` retains missing/unstable readback cases.
`INGEST_BASELINE_MISMATCH` refines `CONTACT_BASELINE_VALUES_DIFFER` using the same
normalized field comparison as projection verification. Only a closed mismatch
category and optional row-kind/component/difference enums are emitted; null means
the classifier does not provide that detail. Its `POST_WRITE_*` category names
reuse the existing classifier vocabulary, not the stage at which this event occurs.
This observation MUST NOT adopt a mismatching baseline or acknowledge native intent;
diagnostic exceptions are isolated and rejection remains unchanged.
`INGEST_COMMIT_REPLAN` exposes the existing unified contact/membership commit
refusal, while `INGEST_MEMBERSHIP_STALE` refines a membership-ledger mismatch.
Both contain only closed enums from the existing guards, without new reads,
identifiers or contact values; they MUST NOT alter rollback or retry behavior.
`PROJECTION_ROW_FAILURE` refines a final failed projection decode with CURRENT or
POST_WRITE stage, the existing row-codec category and optional closed identity
reason/row kind. It uses only the resolver's existing observations, without
additional provider/database reads or contact payloads. A recovered decode MUST
NOT emit this failure; it MUST NOT increment repair counts or change a trust
decision. Null identity detail means unavailable, not a verified binding.
`PROJECTION_BINDING_RECOVERY` identifies the first refused recovery guard. It
carries only the decode stage and closed reason/kind/codec/mismatch/component/
difference enums. A subsequent generic decoder failure MUST NOT replace a more
specific guard. Comparison detail uses the existing decoded snapshots and normal
fingerprint equivalences, without extra contact reads. It does not count another
affected contact or relax any trust condition; null detail means unavailable.
An outcome is the pass result, not proof of field-level convergence. Deferred
Android failures may precede successful remote stages; the final stage alone
MUST NOT be treated as the origin of an error. Retries already collapsed by a
lower boundary may still lack a detailed category.

Events live only in Android's bounded log buffer and may be evicted or lost on
reboot. There is no new on-disk report, telemetry or automatic export. Capture
only the allowlisted diagnostic fields promptly after an authorized
reproduction; raw buffers MUST NOT be printed, committed or shared. A missing
event MUST NOT establish success. Log-read permission does not by itself authorize
installation, app execution or account mutations. Consult the current task scope.

## Commit and publish

Use apply_patch for repository text edits. Inspect the diff, check whitespace,
and exclude generated outputs, personal data, credentials and signing material.
Commit coherent verified work. Do not infer public-release readiness from the
version number or a passing unit suite.

A history rewrite is a separate operation: preserve a verified recovery bundle,
review the proposed root tree and remote references, and use explicit expected
remote values. Do not force-push, delete tags/releases or modify another worktree
as an incidental cleanup action. See [Reset preparation](REPOSITORY_RESET.md).
