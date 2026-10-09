# Functional test plan

Target tests use the real minified Contako app, a physical system contact editor
and the dedicated Proton Web account when available. This is a reusable procedure,
not a history of APK installations or old campaign results. Read [QA](QA.md) for
selection/reporting and [regression coverage](REGRESSION_MATRIX.md) for every
retained defect. Unit and software tests have separate plans.

## Conditions

- Confirm device, account and authorized scope independently before mutations.
  Follow local operator restrictions; this public plan grants no account access.
- Keep the existing session for ordinary contact cases. Existing login/2FA/CAPTCHA
  acceptance is attributed in [Known limitations](KNOWN_LIMITATIONS.md); retest when affected, not as daily setup.
- Use the [three synthetic datasets](../app/qa/dataset/README.md). Record run-owned
  fixture identities only in an ignored local ledger. No prefix-only bulk cleanup.
- Record source/dirty-tree identity, APK hash, build variant, Android/API/editor,
  connectivity and permissions. Verify installed signing/package compatibility.
- Unavailable phone, Web, free-plan account or controllable provider state means
  NOT RUN for that component. A local fake or zero dashboard counters is not a
  substitute for independent intended-field checks.
- Password/security/2FA settings, billing, subscriptions and account deletion are
  outside this plan. Do not reset the app/provider or unrelated address books.

## Nominal creation and editing acceptance gate

Use a minimal and a rich contact, then the imported compatibility set. Exercise
creation from Contako, Web and the owned Android account. A Web import is setup,
not evidence that native/Contako creation works.

For each selected origin:
1. Create once, verify exactly one remote contact and one owned Android copy.
2. Add, replace and remove an email and a multiline note in separate saves,
   alternating Contako -> Web -> Android. On a rich contact, remove one email
   while editing another; keep the other email's group memberships unchanged.
3. Replace photo A with B then C; perform the combined Web email/gender/address
   change below with birthday, alias and hidden fields unchanged.
4. Reopen each peer after every save and compare exact intended and retained values,
   including accents, newlines and unknown properties where a scoped export is
   needed. Android is checked only for fields it can represent.
5. Restart without sign-out, observe a no-change pass, delete the owned fixture
   and verify absence across all peers and after the next pass.

Local Save or normal foreground/background scheduling MUST converge without a
manual repair to pass an automatic nominal step. Record manual recovery separately.
Record the actual propagation trigger; foreground arrival is not background-only
qualification. Continue using the same fixture across edits, not a fresh contact
after each failure. Keep current known limitations visible without erasing prior
successful evidence or declaring an accepted limitation fixed.

## Cases

Every row is parameterized by the referenced dataset/state. Unless stated
otherwise, apply the common setup, exact field comparison and cleanup above.

| ID | Setup and action | Expected observation |
| --- | --- | --- |
| FT-01 | A separately authorized dedicated login: password, TOTP/recovery, CAPTCHA, cancellation, process/session replacement; renderer termination when affected. | Correct protected session lifecycle, no second prompt for the same one-password TOTP secret while valid, no late callback; unsupported security-key-only path is actionable. Existing owner validation remains attributed. |
| FT-02 | C02/C12 in a populated directory; open/clear search, accents/non-Latin query, alphabet drag, detail/back at a partial offset, switch/reselect tabs and recreate. | Correct local matches; search is initially collapsed, clear closes it, both directories preserve query/position, only explicit active-tab reselection returns to top. |
| FT-03 | C01/C02/C13 entered through Contako, Web and native editor separately; blank display name with explicit given/family name; no existing Android ledger. | Durable Save, name fallback, exactly one contact and owned projection; no second edit/manual repair needed; no email fabricated for C13. |
| FT-04 | C03/C04/C08/C09/C12; delete one email while editing another, change name/note/type/primary, use date calendar and manual dd/MM/yyyy or dd/MM. | Reopen and peer values match, other fields survive; invalid date feedback, cancellation preserves input, no invented year. Record yearless Web loss as its known limitation, not a local pass. |
| FT-05 | C03/C04 with G01–G04; add/remove multiple groups on a secondary email, change preferred email, remove an email; try C13. | Exact per-email memberships in Web; Android preferred-email groups only; unrelated edges survive; C13 cannot acquire invented email/groups. |
| FT-06 | Create, recolor across the supported palette, rename and delete groups; select members under a search filter; duplicate-name warning and empty G04. | Filter retains selection, group identities stay distinct, deletion removes assignments only. Then add a native phone after the deleted group's receipt remains. |
| FT-07 | Edit/create/delete own raw contact through the real editor; C03/C14 in separate owned source accounts, standalone and aggregated; clean/dirty own/foreign RCS. | Own intent is durable and uploaded, foreign raw rows untouched, supported opaque RCS preserved; unknown lookalikes fail explicitly. Native foreground pickup works even with empty Room outbox/recent success. |
| FT-08 | C04/C10/C11; selected A/B/C images and logo, legacy binary import, multiple/preferred images, unrelated edit and no-change pass. | Correct image in all applicable peers, canonical bytes preserved on unrelated edits, full image and thumbnail independently verified, no arbitrary remote-image fetch; single-image gallery omitted but preview accessible. |
| FT-09 | C04/C05/C06/C07/C08/C09; alternating Web edits, private-only changes and combined-save sequence below. Establish the event cursor, then change only a note on Web, sync normally, restart and repeat; keep the indexed name/emails unchanged. | Intended fields arrive without repair/public-field nudge; maintained signed/unsigned semantics and preservation hold. A failed hydration retains the event for replay; an unchanged follow-up does not hydrate every card. Verify actual imported syntax/type; normalization by Web does not qualify the original wire variant. |
| FT-10 | C01/C03; local/native edits offline, reconnect, normal foreground return, automatic-sync switches disabled then re-enabled. | Pending intent survives; one eligible serialized runner; automatic convergence when permitted, no false acknowledgement or retry storm. |
| FT-11 | Dedicated phone locked and unplugged, later reboot/first unlock, process restart and natural periodic runs. | Observe actual scheduled work, no attributable crash/ANR or unbounded retry; measure battery over a meaningful interval, not USB-powered CPU snapshots. No exact hourly deadline promised. |
| FT-12 | load-300, optionally load-5000; large import/projection, partial provider failure and resume. Include an interrupted first-photo copy with a missing or mismatching exact photo proof, followed by an unrelated native edit and an acknowledged Proton deletion of another owned contact. | Bounded work, no missing/duplicate contacts or lost intent; independent sampled fields plus complete owned identity/count reconciliation. An unverified photo remains pending with its dirty row, journal and intent intact; it MUST NOT stop ingestion/projection of later contacts or authorize overwriting native edits. The unrelated edit and scoped deletion converge; foreign linked sources survive. Counters alone do not prove full-field equality. |
| FT-12P | Project a patterned and a transparent photo; edit only the native note, then replace the native photo. Repeat after restart and with an interrupted write. Include stale display-file publication and a concurrent native edit before readback. | Original canonical photo bytes remain unchanged after projection and note-only edits. The durable representation receipt matches actual Android bytes and survives journal cleanup/restart; a true photo edit is captured durably and propagated. Ownership/epoch/row/version mismatch, stale display bytes and a lost return without proof remain pending without clearing native intent. No-change sync MUST NOT rewrite the photo. |
| FT-13 | Delete from each supported origin, including unsent creation; restart and next no-change pass. | Scoped deletion/absence in all peers, no resurrection or deletion of another account's row; group deletion never deletes member contacts. |
| FT-14 | C01/C03; offline concurrent updates in both orders, edit versus delete and lost acknowledgement. | Private-only Web edits followed by a local edit MUST block upload and retain both versions. Compare and choose each version in Sync, restart before/after choice, change Proton again before resolution, and test a lost acknowledgement. An obsolete choice MUST NOT overwrite new edits; remote deletion invalidates it. No fabricated timestamp or silent membership loss. |
| FT-15 | Blank and rich drafts: grouped add-field picker, existing fields, photo from avatar, per-email groups, field options; invalid field in a collapsed section, both Save controls, repeated taps, failure/retry and back. | Empty optional families remain hidden until added; existing values remain reachable. Label/preference/order controls and per-email assignments preserve other fields. Invalid field revealed, busy state, no duplicate submit; draft retained until success or explicit discard. |
| FT-16 | C12 long/accented/non-Latin text entered with the real keyboard, paste and multiline editing. | Exact input before Save and after reopen; do not classify fast ADB injection loss as an IME defect. |
| FT-17 | Initial sync with pending Android copies (scheduled/running/idle), then real blocked upload/projection, excluding search filter, group/global error, zero-outbox retry, missing permission; then recovery. | Ordinary pending copies MUST NOT create attention items or a warning badge. Published Android partial failure MUST expose outstanding copies; real conflicts and blocked uploads remain visible during sync. Count/list/contact navigation agree without double counting; pending Proton and Android work remain distinct. After recovery, new ordinary pending copies MUST NOT revive the warning. Notification delivery is a separate implementation-dependent variant, not passed by claim-storage tests. |
| FT-18 | Dedicated account with unavailable groups, if available; permission grant/revocation and capability refresh. | Ordinary contacts still work, unavailable group mutations explained/disabled, refresh preserves data. No subscription/security-setting changes for setup. |
| FT-19 | All locales, light/dark/system, manual theme override, portrait/landscape, 200% font, TalkBack, expanded layout and another available OEM. Include a long account address, About and Usage menu entries, GitHub/license actions and contacts with/without unsupported properties. | Menu address aligns and wraps; GitHub opens the project URL and license details open the maintained licensing page. About and Usage menu entries open separate pages directly, without tabs; both scroll fully and Back restores the directory context. Actual preserved fields expose selectable read-only names/values; source-card backups alone create no preserved-field notice. Idle contact detail has no Android-compatibility badge. Readable fields/actions/flags/brand, accessible back/icon labels, no clipped dialogs; launcher mascot and monochrome masks remain usable. No forced personal sign-out for login artwork. |
| FT-20 | C04 full repair with pending edits; interrupt after hydration/context advancement, resume and cancel at safe boundaries; off-Wi-Fi confirmation. | Fresh guarded projection context, visible resume/finalize cancellation, cached browsing and durable intent preserved; checkpoint cleared only on completion/cancellation. Recovery on a second pass is not first-pass success. |
| FT-21 | Converged fixtures, unchanged pass and unchanged foreground return. | No full-card fetch for every unchanged contact, no provider rewrite/identity churn. Measure request/write counts separately from a green status; 5-second target needs a measured start. |
| FT-22 | load-300 initial import and ten-contact delta, load-5000 repair only when needed. | Record start/end and environment; targets 90 s / 15 s / 15 min respectively, not inferred from final counts. Compare field correctness independently. |
| FT-23 | Separately planned dedicated lifecycle: dirty native contact/group/tombstone with empty Room outbox, offline/unavailable provider, edit racing cleanup; Cancel then successful sync then sign-out again. | Fresh warning state, Sync/Cancel/confirmed Discard as appropriate; unsafe cleanup refuses and retains session. Normal sign-out/account removal removes scoped local copies, never remote contacts. |
| FT-24 | C02/C03/C04/C14: alias vs structured names, first/family edits, clear display name, generated names, separate/aggregated sources and first-photo baseline. Include an email-only display alias with empty given/family names; inspect native generated parts, sync unchanged, then edit only the native note. | Names persist without replacing alias or importing generated parts as edits; actual source-owned values compared. The email alias retains its punctuation and canonical given/family names remain empty after unchanged sync and the unrelated note edit. A genuine native name edit remains detectable. Similar aggregate labels never authorize forced baseline adoption or merging. |
| FT-25 | Rich contact with Call/Message/Email actions, normal and narrow screen, long locale labels and large text. | Three actions share a row when measured labels fit; deliberate large-text reflow allowed; primary badges align with values. |
| FT-26 | Generate/export/dismiss diagnostics on ordinary and diagnostic variants; correlate an authorized real failure, picker cancel/provider error. | Fixed-schema sanitized output, no credentials/contact values/IDs, no false success from missing logs; version-suffix defect remains explicit until fixed. Deleting the displayed report does not delete an exported file. |

## Required regressions for each functional campaign

Select rows from [the regression matrix](REGRESSION_MATRIX.md) according to changed
code and the installed variant. Always include a relevant nominal path and
no-change/cleanup check. A release candidate should cover the full applicable
target matrix, with any unavailable or accepted-limitation cells explicitly
recorded. Do not run every historical helper for every small change.

### Native ingestion and bounded projection regressions

For FT-03/FT-07/FT-12, create a rich contact through the native editor with a
photo, accented names, multiple emails and a note. Verify durable creation and
the exact intended fields in Contako and Proton. Interrupt before native
acknowledgement and resume: there MUST be one canonical contact and one creation
intent, and a changed photo MUST NOT replay an old receipt.

Place an incompatible owned contact before another valid native edit and a
scoped deletion. The incompatible row MUST retain its intent without being
acknowledged; later contacts MUST still converge and the result MUST remain
partial. Repeat for malformed supported content and unsupported MIME, separately
from account/epoch, identity, catalog and storage failures, which MUST stop safely.
Never use an aggregate display or a surviving foreign linked source as the oracle.

Use rich-photo pages whose combined payload exceeds the Android page byte
budget while each contact remains within individual limits. Acquisition MUST
advance with smaller stable pages, without missing or duplicating contacts.
An oversized individual contact MUST remain explicit. Measure concurrent photo
projection separately from functional equality; successful convergence does not
establish latency or battery targets.

For FT-17, distinguish a denied contact upload from a denied group operation,
including a stored denial from an older installation. The message MUST identify
the applicable boundary without claiming a group failure for a contact. In an
isolated provider fault scenario, remove one owned raw copy without a tombstone;
normal sync MUST NOT report that stored CLEAN binding as a verified current copy
or adopt another source to replace it.

### Ordered photo replacement regression

For C04/C10: establish photo A and exact alias/names/phonetics/notes/emails/groups/
address/full birthday. Edit an unrelated Web field, verify arrival, replace only
the preferred photo in Contako with B, verify every peer, then repeat with C.
Restart and check no-change. Next perform ONE Web save adding email and gender
and modifying the street while birthday/photo/alias/groups remain unchanged.
Compare the combined result, then separately repeat each field edit to isolate
a failure; isolated successes do not replace the combined case.

For controlled SW-07 state, separately test relocated name and birthday rows in
a pending batch, organization/title/role bindings, completed name baseline and
interruption before recovery. Repeat with a genuine native edit. Reject wrong
bytes, DIRTY state, changed managed values, occupied destination, old locator
still present, stale epoch/revision and forged phonetic/primary claims. Record
whether the provider state actually occurred; ordinary photo replacement is not
proof of internal journal recovery.

For FT-12/SW-07, interrupt the first membership initialization between ledger and
baseline persistence. New initialization MUST roll back both records on failure.
For legacy interrupted state, ordinary sync MAY reconstruct only the exact
initial marker's empty snapshot under unchanged account/epoch/owned contact
context; this MUST NOT mark the contact synchronized. Observe current owned rows
and verify actual group projection and all untouched fields before completion.
A missing completed, pending, detached or mismatching baseline MUST remain blocked;
native DIRTY state MUST be ingested before any initialization. Retry and restart
MUST preserve existing references and per-email group assignments.

### Display alias and separate native source regression

For FT-24, use C03/C14 with a photo on the Contako fixture. Create the second
source through the system editor on a dedicated test device, using the same
email/phone but a different given name. Select an explicitly authorized account
or device-only storage; do not use a third-party account merely because the
editor selects it by default. Exercise both setup orders: the native duplicate
before the first Contako projection, and after a completed projection.

Compare Contako's display alias, given/family names and the source-owned Android
name row independently of the aggregate title. Find the contact by email in the
general directory and from the attention list when applicable. Add a phone from
the native editor, then separately change the given name; verify the intended
change and preservation of the distinct alias, photo, other fields and foreign
source in Contako, Android and Proton. An unchanged pass MUST NOT rewrite the
owned rows. Record the actual editor and OS: Google Contacts acceptance does not
qualify Samsung Contacts on another Android release.

Keep interrupted first-photo recovery separate from this completed-baseline
journey. With no completed baseline, matching given/family names and coordinates
MUST NOT authorize replacing an explicit alias with a generated display name or
clearing native intent. Record interruption as NOT RUN unless the pending journal
and missing baseline were actually established; an ordinary successful sync is
not evidence of recovery.

### Private-only remote changes and imported formats

On C04 separately modify note, gender, address and photo in Web while leaving
public names/emails/groups unchanged. No repair or extra public-field edit may
serve as the trigger. Inspect intended values and hydration evidence.
C05/C06 require confirmed clear/unsigned card layouts; C07 requires equivalent
parameter repetition preserved by the deployed path. N01/N02 remain isolated
negative tests; do not upload malformed/oversized cards to live accounts.

### Image and date boundaries

Picker images: ordinary large portrait/landscape, square, small, long panorama,
transparent logo, corrupt/mismatched MIME, oversize input and an EXIF-rotated JPEG
when available. Check 180-pixel short-edge target, 1024-pixel long-edge ceiling,
no enlargement/crop, orientation and alpha. Imported bytes stay unchanged during
ordinary edits. Confirm full image and thumbnail independently on the provider.
A changed file locator alone is not image loss.

Dates: full and yearless February 29, invalid February 30, restore explicit year,
calendar cancel/back, dd/MM manual entry, unchanged compact/text imports and
local custom date events. No timezone day shift or fabricated year. Full-date
control must accompany the known yearless Web compatibility case.

## End each mutation case

Record intended/retained-field oracles for each peer, pending/attention state,
actual trigger, no-change behavior, failures/recovery and exact cleanup scope.
Keep raw screenshots, exports and private locators only in ignored app/build.
Use the [result template](testing/RESULT_TEMPLATE.md); results do not accumulate
inside this procedure. A deferred non-blocking defect is a documented limitation,
not a reason to restart development or to mark an unexecuted case passed.

## Concurrent edits and recovery variants

For photo projection, link the owned contact to a foreign source and select the
owned photo as the aggregate default in the native editor. A change to Android's
photo super-primary flag alone MUST NOT create a Proton edit or a perpetual
copy obligation. Replace the actual photo separately: its bytes MUST still be
delivered and read back with the guarded receipt, while preserving the existing
native aggregate photo preference. Persisted provider baselines MUST retain
their actual flags and remain verifiable after upgrading the application.

For FT-06/FT-15, use a rich contact with two emails and different group assignments.
Change only the secondary email's groups, then make it preferred; repeat the
preference switch in both directions. Verify each email's independent Proton
assignments, the Android owned email rows' primary flags and membership following
the preferred email. A successful upload alone MUST NOT qualify Android projection.
Restart and run a no-change pass; preference and memberships MUST remain stable.

For FT-06/FT-07, establish two distinct emails with two different groups each
and two phone numbers. Delete one phone through the native contact editor,
without changing emails or groups. Repeat with a foreign raw contact linked to
the owned Contako source, selecting that source explicitly when the editor asks.
Then edit the owned surname through another available native editor. Both edits
MUST reach Contako and Proton without repair; all four per-email group edges
MUST survive ingestion, remote reconciliation and a subsequent no-change pass.
A number retained by the foreign source MAY remain visible in the aggregate;
it MUST NOT reappear in the owned Contako source or Proton. Compare each source
independently and retain any failing pending state for diagnosis.

Repeat with the same email address in two differently labelled rows, each with
two group assignments: equal groups on both rows, then overlapping but different
groups. Include repeated phone, postal address and URL values with different
labels and accented names. Test both one shared Proton email identity and two
distinct ordered identities, including different assignments per occurrence.
Delete a postal address and add a phone in the native editor without touching
emails. Both email rows, labels and their assignments MUST survive hydration,
contact acknowledgement, group upload and a subsequent no-change pass.
Ambiguous service multiplicity or order MUST remain blocked rather than guessing
which email occurrence owns a group. Include a repair retry of already blocked
assignments and compare each occurrence independently on Proton.
For an older assignment conflict caused by a missing identity on a repeated row,
ordinary sync MUST refresh only the affected unchanged card, preserve pending
occurrence-level memberships and reconcile before retrying the assignment.
Permission denials, contact conflicts, deleted groups and stale revisions MUST
remain blocked. A subsequent no-change pass MUST NOT repeat the repair hydration.

RepeatedEmailNativeJourneyDeviceTest provides an opt-in retained two-contact,
three-group fixture and independently compares Room, fresh verified Proton cards,
remote group members and source-owned Android rows. Its provider mutations use
ordinary native DIRTY handling. They qualify the provider boundary, not touch/IME
behavior in a specific contact editor; repeat the editor journey separately.
Preparation requires an existing authorized clean session, without account
creation or repair. Link its second contact to an authorized foreign source for
the source-isolation variant and retain the exact private fixture journal.
The fixture uses repeated URLs without labels to isolate email-group behavior.
Run URL-label preservation separately: create labelled URLs, then perform a
remote adoption and a no-change pass. Compare local and owned Android labels
against the original intent even though Proton omits unsupported URL types.
A successful status MUST NOT hide lost labels; see [known limitations](KNOWN_LIMITATIONS.md).

Extend FT-14/FT-17 with the rich and imported fixtures, comparing changed and
untouched values independently on each peer:

- Change only a Web note/photo/unknown field, then another local field; public
  directory equality MUST NOT permit a stale upload. Both snapshots survive restart.
- Choose Contako and Proton separately. No second choice while an upload is in
  progress; network failure retains intent; a newer local/remote edit requires review.
  Open comparison with structured names and preserved read-only fields; every
  canonical field type MUST have a readable label without crashing.
- Remove an email on Proton while its group assignments are pending locally.
  Proton adoption is disabled with an explanation; assignments are never discarded.
  Choose the local version to restore that email. Dependent assignments MUST wait
  for contact acknowledgement and use the restored Proton email identity. Older
  failures caused by the replaced identity MUST recover through ordinary sync,
  with fresh membership reconciliation; no unrelated group edit or repair is required.
  Repeat with a removed assignment: an empty desired membership MUST still wait
  for the pending contact, and the removed label MUST remain absent after restoration.
- Confirm remote deletion after a conflict: no stale queued-choice display,
   no orphaned conflict after a pending delete converges, and no implicit recreation.
   After a remote deletion has already been reconciled, explicitly delete the
   retained local draft, restart and run another ordinary pass. The pending delete
   MUST converge on fresh targeted absence proof, remove its owned Android copy
   and clear attention without manual repair; a failed absence check MUST retain intent.
- Interrupt group creation while editing/deleting it; a received remote identity
  attaches to the current revision. An ambiguous creation is never replayed by name.
- Open an editor, update the canonical contact/remote identity/assignments, then
  save the old draft. Reject the stale save and retain the draft. While saving or
  reading a selected photo, input/save actions cannot silently lose the last edit.
- Exercise structured-name cards with public FN before private N, accented names,
  highly compressed large photos, unsupported group operations and diagnostic
  builds. Qualify the minified variant, not only JVM policy tests.
- For FT-17 alerts: deny permission, grant it later, restart after publication,
  resolve the block, and open notifications cold/warm. No contact/account data in
  the notification; no consumed claim after denied/failed publication or stale
  cancellation of a newly published alert. Natural scheduling remains separate.
- For FT-23 sign-out: verify remote revocation is attempted before local session
  destruction; offline cleanup succeeds, but pending local/native intent still
  blocks an unconfirmed discard. Test CAPTCHA renderer loss on an isolated target.
- For FT-15/FT-07 composite organization rows: save a title without a company,
  then separately replace the title, add/remove a role and add/remove a company.
  Include a second save before the first projection completes. Verify exact
  values and durable linked identities after each save in all peers, restart and
  repeat a no-change pass. No divergent binding, pending Android copy or false
  acknowledgement may remain; ownership/version guards MUST stay effective.
