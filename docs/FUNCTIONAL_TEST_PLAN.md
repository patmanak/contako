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
| FT-09 | C04/C05/C06/C07/C08/C09; alternating Web edits, private-only changes and combined-save sequence below. | Intended fields arrive without repair/public-field nudge; maintained signed/unsigned semantics and preservation hold. Verify actual imported syntax/type; normalization by Web does not qualify the original wire variant. |
| FT-10 | C01/C03; local/native edits offline, reconnect, normal foreground return, automatic-sync switches disabled then re-enabled. | Pending intent survives; one eligible serialized runner; automatic convergence when permitted, no false acknowledgement or retry storm. |
| FT-11 | Dedicated phone locked and unplugged, later reboot/first unlock, process restart and natural periodic runs. | Observe actual scheduled work, no attributable crash/ANR or unbounded retry; measure battery over a meaningful interval, not USB-powered CPU snapshots. No exact hourly deadline promised. |
| FT-12 | load-300, optionally load-5000; large import/projection, partial provider failure and resume. | Bounded work, no missing/duplicate contacts or lost intent; independent sampled fields plus complete owned identity/count reconciliation. Counters alone do not prove full-field equality. |
| FT-13 | Delete from each supported origin, including unsent creation; restart and next no-change pass. | Scoped deletion/absence in all peers, no resurrection or deletion of another account's row; group deletion never deletes member contacts. |
| FT-14 | C01/C03; offline concurrent updates in both orders, edit versus delete and lost acknowledgement. | Current D-096 missing-remote-time fallback is honored for updates; no fabricated timestamp. Incomparable edit/delete exposes recovery and retains intent. |
| FT-15 | Blank and rich drafts: grouped add-field picker, existing fields, photo from avatar, per-email groups, field options; invalid field in a collapsed section, both Save controls, repeated taps, failure/retry and back. | Empty optional families remain hidden until added; existing values remain reachable. Label/preference/order controls and per-email assignments preserve other fields. Invalid field revealed, busy state, no duplicate submit; draft retained until success or explicit discard. |
| FT-16 | C12 long/accented/non-Latin text entered with the real keyboard, paste and multiline editing. | Exact input before Save and after reopen; do not classify fast ADB injection loss as an IME defect. |
| FT-17 | Initial sync with pending Android copies (scheduled/running/idle), then real blocked upload/projection, excluding search filter, group/global error, zero-outbox retry, missing permission; then recovery. | Ordinary pending copies MUST NOT create attention items or a warning badge. Published Android partial failure MUST expose outstanding copies; real conflicts and blocked uploads remain visible during sync. Count/list/contact navigation agree without double counting; pending Proton and Android work remain distinct. After recovery, new ordinary pending copies MUST NOT revive the warning. Notification delivery is a separate implementation-dependent variant, not passed by claim-storage tests. |
| FT-18 | Dedicated account with unavailable groups, if available; permission grant/revocation and capability refresh. | Ordinary contacts still work, unavailable group mutations explained/disabled, refresh preserves data. No subscription/security-setting changes for setup. |
| FT-19 | All locales, light/dark/system, manual theme override, portrait/landscape, 200% font, TalkBack, expanded layout and another available OEM. Include a long account address, About and Usage menu entries, GitHub/license actions and contacts with/without unsupported properties. | Menu address aligns and wraps; GitHub opens the project URL and license details open the maintained licensing page. About and Usage menu entries open separate pages directly, without tabs; both scroll fully and Back restores the directory context. Actual preserved fields expose selectable read-only names/values; source-card backups alone create no preserved-field notice. Idle contact detail has no Android-compatibility badge. Readable fields/actions/flags/brand, accessible back/icon labels, no clipped dialogs; launcher mascot and monochrome masks remain usable. No forced personal sign-out for login artwork. |
| FT-20 | C04 full repair with pending edits; interrupt after hydration/context advancement, resume and cancel at safe boundaries; off-Wi-Fi confirmation. | Fresh guarded projection context, visible resume/finalize cancellation, cached browsing and durable intent preserved; checkpoint cleared only on completion/cancellation. Recovery on a second pass is not first-pass success. |
| FT-21 | Converged fixtures, unchanged pass and unchanged foreground return. | No full-card fetch for every unchanged contact, no provider rewrite/identity churn. Measure request/write counts separately from a green status; 5-second target needs a measured start. |
| FT-22 | load-300 initial import and ten-contact delta, load-5000 repair only when needed. | Record start/end and environment; targets 90 s / 15 s / 15 min respectively, not inferred from final counts. Compare field correctness independently. |
| FT-23 | Separately planned dedicated lifecycle: dirty native contact/group/tombstone with empty Room outbox, offline/unavailable provider, edit racing cleanup; Cancel then successful sync then sign-out again. | Fresh warning state, Sync/Cancel/confirmed Discard as appropriate; unsafe cleanup refuses and retains session. Normal sign-out/account removal removes scoped local copies, never remote contacts. |
| FT-24 | C02/C03/C04/C14: alias vs structured names, first/family edits, clear display name, generated names, separate/aggregated sources and first-photo baseline. | Names persist without replacing alias or importing generated parts as edits; actual source-owned values compared. Similar aggregate labels never authorize forced baseline adoption or merging. |
| FT-25 | Rich contact with Call/Message/Email actions, normal and narrow screen, long locale labels and large text. | Three actions share a row when measured labels fit; deliberate large-text reflow allowed; primary badges align with values. |
| FT-26 | Generate/export/dismiss diagnostics on ordinary and diagnostic variants; correlate an authorized real failure, picker cancel/provider error. | Fixed-schema sanitized output, no credentials/contact values/IDs, no false success from missing logs; version-suffix defect remains explicit until fixed. Deleting the displayed report does not delete an exported file. |

## Required regressions for each functional campaign

Select rows from [the regression matrix](REGRESSION_MATRIX.md) according to changed
code and the installed variant. Always include a relevant nominal path and
no-change/cleanup check. A release candidate should cover the full applicable
target matrix, with any unavailable or accepted-limitation cells explicitly
recorded. Do not run every historical helper for every small change.

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
