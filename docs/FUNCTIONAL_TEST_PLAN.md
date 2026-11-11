# Functional test plan

For mutation interruption changes, use controlled synthetic contacts with repeated
fields and per-email groups. Compare Contako, Proton and the source-owned Android
copy after creation, successive edits, restart and deletion. A newer edit made
during a submitted write MUST remain pending until its own acknowledgement.
For a delivered CREATE with a deliberately withheld response, verify one remote
identity and one local aggregate after editing or deleting it. A never-delivered
CREATE must have a recoverable verification path without blind replay. Use
controlled fault injection; ordinary successful creation does not qualify the
lost-response case. Complete the corresponding Room/software cases as well.

After a native-bundle rebuild, exercise real decrypt/verify/sign operations and a
minified contact round trip on a physical runtime. Record the device's actual
page size. ELF/ZIP alignment checks and 4 KiB-device execution MUST remain distinct
from 16 KiB-device qualification.

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

For directory filters, compare alias-only cards with generated and distinct
display/given/family names, structured names, nicknames, phonetic names, groups
and recognized technical messaging settings. These alone MUST NOT prevent hiding.
Add photo, phone, note and useful opaque-field variants, including unknown
X-PM-/X-CONTAKO- extensions; they MUST remain visible. Include mixed/unknown
domains and email-as-display-name cards. Only otherwise alias-only cards on
recognized exact domains MUST disappear when that criterion is applied, without
changing encoded cards, canonical names, groups, peer data or Sync issues.
Cancel or remove its chip and check the original identities and fields return.
Use rich contacts with duplicate emails, secondary-email
groups, multiple organizations, blank values, a nonblank invalid phone and a
local date. Apply/cancel, remove criteria, combine criteria with company-name search, clear
filters without clearing search, open/back and rotate at normal/200% text.
Compare exact identities/counts and original fields. A contact leaving criteria
after an edit MUST keep its detail open; Sync MUST retain all issues and counts.
Check offline/permission-denied use, explicit local read failure/retry and owner
cleanup without a peer request caused by filtering. A fresh process MUST start
without criteria. Compare first import, unchanged delayed upload/read and a real
field change: only the last renews observation time. Group-only edits MUST NOT
renew it. The date is local observation, never a claimed Proton modification date.
Choose 1, 7 and 30 days; verify both time-window boundaries and the active chip.
An empty, zero or overflowing day input MUST NOT apply stale criteria. Cancel and
rotation MUST preserve the appropriate draft/applied duration. Check repeated
upward sheet drags and numeric keyboard use at normal/200% text: title/options
stay within the window and Apply/Cancel remain reachable. No company checklist
or explanatory paragraphs belong in this sheet.

For performance changes, follow the comparable [measurement protocol](QA.md#performance-measurements).
Keep local Room/Compose evidence separate from these real-peer journeys:

- With 300 contacts/20 groups, then 5,000/200, open cached content and search
  during an active import. Edit a committed rich contact while other cards and
  photos are pending; the latest durable edit and preserved fields MUST survive.
- Validate ten native edits from the editor gesture through detection, serialized
  queue, Proton acknowledgement and Android verification. Record each boundary
  rather than attributing the whole delay to network or CPU.
- On a converged address book, measure an ordinary no-change pass and foreground return.
  Require zero full-card fetches for unchanged contacts and zero unnecessary
  provider rewrites, independently of final status counters.
- Include one rejected card among healthy records, correct it and resume. Keep
  healthy records usable and the rejected obligation durable; recovery cost and
  nominal convergence MUST have separate results.
- Interrupt after a peer write before acknowledgement, then resume. Verify one
  identity/copy, preserved intent and bounded replay, including photo receipts.
  After a 5,000-contact repair, check rest without repeated work and qualify
  unplugged energy separately. USB execution MUST NOT establish battery life.

Keep median/P95/max and failed attempts in local evidence. Fewer than thirty local
samples, unmeasured native memory, simulated peers or unavailable OEM conditions
MUST remain explicit limitations rather than passing the complete target.

For targeted recovery, use a rejected card or Android-copy issue among 300 owned
synthetic contacts. Preview/cancel MUST make no peer request. Confirm once and
repeatedly; inspect a scoped result, restart before/after effect and resume.
Compare the intended and preserved fields, duplicate occurrences, per-email
groups, photo originals/receipts and at least one unrelated and one linked
foreign-source witness. A Proton recheck MUST read only the selected full card
without advancing the event cursor or uploading edits. An Android retry MUST
capture a native edit made after the preview and never overwrite it with an old
plan; it MUST make no Proton request or unrelated contact-copy write.
Exercise permission/session loss, provider-epoch change, recycled locator,
unestablished identity, 429/Retry-After and a refused proof. Requests and backoff
MUST survive restart; ten taps MUST NOT reset them. An ambiguous 404 MUST NOT
delete a contact. A real conflict remains a conflict. A resolved target MUST NOT
erase another issue, fabricate an account error or advance global success time.
Keep coalesced normal/full synchronization distinct from isolated targeted cost;
check both a queue beyond one batch and a future retry deadline at startup.
Include a copy pending without a per-contact error: deny Contacts permission,
request its recovery, then delete the canonical contact and run ordinary sync.
The recovery MUST NOT claim verified removal while the Android copy remains.
Reopen and resolve only after the current deletion proof. For a rejected Proton
card, change the next attempt's refusal to authentication, cryptography or
permission; the latest scoped cause/action MUST survive restart without changing
another issue or the last whole-account success time.

For actionable issues, combine an Android issue, rejected Proton card, blocked
group and account-authentication prerequisite. Each independent scope MUST stay
visible with its own known cause; resolving one MUST NOT erase the others.
Check permission/settings return, version comparison and queued choices, named
deletions without an edit action, unknown cause and diagnostics navigation.
No field, saved-state promise or reconnect advice may be guessed. Ten retry
taps and navigation/recreation MUST preserve eligibility and intent. A healed
historical journal entry MUST NOT recreate a current badge. Run these cases
with long localized text, 200% font and both themes; report physical coverage
separately from presentation-policy tests.
Known missing-name and invalid-email causes, including both together, MUST keep
their specific instructions. An unknown or remote generic validation refusal
MUST retain conservative advice without inventing a field or email occurrence.

For first import, inspect the dismissible availability overview before and after
the first durable batch, with Android permission granted and denied. Browse an
already committed contact, dismiss/rotate/restart and return to Sync. Verify
the three compact counts (Proton, Contako, system) against independent owned
records, not emitted event totals. Unknown totals MUST show pending, confirmed
empty inventories MUST show zero, and system copies MUST count acknowledged
active contacts in the current provider epoch, excluding failures and deletion
backlog. Verify in-progress, completed and completed-with-errors states, with
only short relevant error summaries. A partial
card rejection MUST retain healthy contacts; an empty confirmed inventory MUST
finish without an endless animation. An idle interrupted import MUST NOT show
live activity merely from an old log/checkpoint. Compare source fields and
foreign contact rows unchanged; opening/closing the overview MUST make no
network request or new synchronization intent.
Also grant Contacts permission while the Android account/context is not ready
or the provider is unavailable. The overview MUST distinguish those states from
normal copy backlog and keep committed contacts accessible. A late prerequisite
result after session invalidation MUST NOT replace the new session's unknown state.

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
| FT-07 | Edit/create/delete own raw contact through the real editor; C03/C14 in separate owned source accounts, standalone and aggregated; add RCS, profile-card, arbitrary vendor, custom, IM/SIP/identity MIME rows, including opaque BLOBs and malformed opaque claims. | Supported intent is durable and uploaded; unmapped fields remain unchanged in Android without blocking the contact or entering Proton. Foreign raw rows remain untouched. A previously managed row that becomes opaque MUST NOT be inferred as deletion. Native foreground pickup works even with empty Room outbox/recent success. |
| FT-08 | C04/C10/C11; selected A/B/C images and logo, legacy binary import, multiple/preferred images, unrelated edit and no-change pass. | Correct image in all applicable peers, canonical bytes preserved on unrelated edits, full image and thumbnail independently verified, no arbitrary remote-image fetch; single-image gallery omitted but preview accessible. |
| FT-09 | C04/C05/C06/C07/C08/C09; alternating Web edits, private-only changes and combined-save sequence below. Establish the event cursor, then change only a note on Web, sync normally, restart and repeat; keep the indexed name/emails unchanged. | Intended fields arrive without repair/public-field nudge; maintained signed/unsigned semantics and preservation hold. A failed hydration retains the event for replay; an unchanged follow-up does not hydrate every card. Verify actual imported syntax/type; normalization by Web does not qualify the original wire variant. |
| FT-09E | Establish a complete baseline, then synchronize unchanged; separately create, rename, edit a private note/photo, delete a contact, and create/rename/recolor/delete owned groups on Web. Add/remove memberships on repeated identical emails with same and different groups. Delete a group with members without changing its contacts. Interrupt before cursor commit and restart. | Unchanged pass reads events without a contact/email/group catalog crawl. Targeted reads recover exact changed data and unchanged fields; opaque occurrences remain distinct. Group deletion removes its assignments without deleting contacts. Unavailable group work leaves a durable catalog-rescan obligation; replay is idempotent. Counters alone are insufficient. |
| FT-09R | Upgrade an older database with pending intent, run full repair, inject Refresh/expired cursor, an unresolved email ID, an omitted targeted group and an interruption after a durable contact batch. Exercise a due seven-day safety scan and clock reversal in controlled software. | Complete baseline/recovery retains pending intent and unknown data. Capture before acquisition; consume no uncommitted interval. Targeted absence confirmation still precedes canonical deletion; a missing email or generic 404 is not proof. Pending/ignored hydration obligations retain their revision and retry rules. |
| FT-09I | Between synchronizations, create then edit a contact on Web, replacing its email IDs; separately create then delete one. Include a locally acknowledged creation absent from the previous checkpoint. Inject malformed/unclassified event responses at and before the safety deadline. | An unknown deleted email causes lightweight acquisition without rereading healthy full cards. Active private changes still hydrate; explicit deletion still requires absence proof, including outside the checkpoint. A contradictory or failed acquisition retains the cursor. At the safety deadline only one latest-position recovery is attempted; authentication, permission, quota and network/service failures retain ordinary handling. |
| FT-10 | C01/C03; local/native edits offline, reconnect, normal foreground return, automatic-sync switches disabled then re-enabled. | Pending intent survives; one eligible serialized runner; automatic convergence when permitted, no false acknowledgement or retry storm. |
| FT-11 | Dedicated phone locked and unplugged, later reboot/first unlock, process restart and natural periodic runs. | Observe actual scheduled work, no attributable crash/ANR or unbounded retry; measure battery over a meaningful interval, not USB-powered CPU snapshots. No exact hourly deadline promised. |
| FT-12 | load-300, optionally load-5000; large import/projection, partial provider failure and resume. Include an interrupted first-photo copy with a missing or mismatching exact photo proof, followed by an unrelated native edit and an acknowledged Proton deletion of another owned contact. | Bounded work, no missing/duplicate contacts or lost intent; independent sampled fields plus complete owned identity/count reconciliation. An unverified photo remains pending with its dirty row, journal and intent intact; it MUST NOT stop ingestion/projection of later contacts or authorize overwriting native edits. The unrelated edit and scoped deletion converge; foreign linked sources survive. Counters alone do not prove full-field equality. |
| FT-12P | Project a patterned and a transparent photo; edit only the native note, then replace the native photo. Repeat after restart and with an interrupted write. Include stale display-file publication and a concurrent native edit before readback. | Original canonical photo bytes remain unchanged after projection and note-only edits. The durable representation receipt matches actual Android bytes and survives journal cleanup/restart; a true photo edit is captured durably and propagated. Ownership/epoch/row/version mismatch, stale display bytes and a lost return without proof remain pending without clearing native intent. No-change sync MUST NOT rewrite the photo. |
| FT-13 | Delete from each supported origin, including unsent creation; restart and next no-change pass. | Scoped deletion/absence in all peers, no resurrection or deletion of another account's row; group deletion never deletes member contacts. |
| FT-14 | C01/C03; offline concurrent updates in both orders, edit versus delete and lost acknowledgement. | Private-only Web edits followed by a local edit MUST block upload and retain both versions. Compare and choose each version in Sync, restart before/after choice, change Proton again before resolution, and test a lost acknowledgement. An obsolete choice MUST NOT overwrite new edits; remote deletion invalidates it. No fabricated timestamp or silent membership loss. |
| FT-15 | Blank and rich drafts: grouped add-field picker, existing fields, photo from avatar, per-email groups, field options; invalid field in a collapsed section, both Save controls, repeated taps, failure/retry and back. | Empty optional families remain hidden until added; existing values remain reachable. Label/preference/order controls and per-email assignments preserve other fields. Invalid field revealed, busy state, no duplicate submit; draft retained until success or explicit discard. |
| FT-16 | C12 long/accented/non-Latin text entered with the real keyboard, paste and multiline editing. | Exact input before Save and after reopen; do not classify fast ADB injection loss as an IME defect. |
| FT-17 | Initial sync with pending Android copies (scheduled/running/idle), then identified ingestion/projection failures, blocked contact/group uploads, deleted contacts, group/global error, zero-outbox retry and missing permission; then restart and recovery. Repeat with an excluding directory search and a higher-priority authentication error. | Ordinary pending copies MUST NOT create attention items or a warning badge. Identified Android failures MUST retain their affected contact even with an old CLEAN ledger, zero outbox or another global reason. Blocked uploads MUST identify the contact or group, including pending deletions. Count/list/read-only navigation agree without double counting or depending on directory search. Pending Proton and Android work remain distinct; resolving one failure MUST NOT clear another. After verified recovery, new ordinary pending copies MUST NOT revive the warning. Notification delivery is a separate implementation-dependent variant, not passed by claim-storage tests. |
| FT-18 | Dedicated account with unavailable groups, if available; permission grant/revocation and capability refresh. | Ordinary contacts still work, unavailable group mutations explained/disabled, refresh preserves data. No subscription/security-setting changes for setup. |
| FT-19 | All locales, light/dark/system, manual theme override, portrait/landscape, 200% font, TalkBack, expanded layout and another available OEM. Include a long account address, About and Usage menu entries, GitHub/license actions and contacts with/without unsupported properties. | Menu address aligns and wraps; GitHub opens the project URL and license details open the maintained licensing page. About and Usage menu entries open separate pages directly, without tabs; both scroll fully and Back restores the directory context. Actual preserved fields expose selectable read-only names/values; source-card backups alone create no preserved-field notice. Idle contact detail has no Android-compatibility badge. Readable fields/actions/flags/brand, accessible back/icon labels, no clipped dialogs; launcher mascot and monochrome masks remain usable. No forced personal sign-out for login artwork. |
| FT-20 | C04 full repair with pending edits; interrupt after hydration/context advancement, resume and cancel at safe boundaries; off-Wi-Fi confirmation. | Fresh guarded projection context, visible resume/finalize cancellation, cached browsing and durable intent preserved; checkpoint cleared only on completion/cancellation. Recovery on a second pass is not first-pass success. |
| FT-21 | Converged fixtures, unchanged pass and unchanged foreground return. | No full-card fetch for every unchanged contact, no provider rewrite/identity churn. Measure request/write counts separately from a green status; 5-second target needs a measured start. |
| FT-22 | load-300 initial import and ten-contact delta, load-5000 repair only when needed. | Record start/end and environment; targets 90 s / 15 s / 15 min respectively, not inferred from final counts. Compare field correctness independently. |
| FT-23 | Separately planned dedicated lifecycle: dirty native contact/group/tombstone with empty Room outbox, offline/unavailable provider, edit racing cleanup; Cancel then successful sync then sign-out again. | Fresh warning state, Sync/Cancel/confirmed Discard as appropriate; unsafe cleanup refuses and retains session. Normal sign-out/account removal removes scoped local copies, never remote contacts. |
| FT-24 | C02/C03/C04/C14: alias vs structured names, first/family edits, clear display name, generated names, separate/aggregated sources and first-photo baseline. Include an email-only display alias with empty given/family names; inspect native generated parts, sync unchanged, then edit only the native note. | Names persist without replacing alias or importing generated parts as edits; actual source-owned values compared. The email alias retains its punctuation and canonical given/family names remain empty after unchanged sync and the unrelated note edit. A genuine native name edit remains detectable. Similar aggregate labels never authorize forced baseline adoption or merging. |
| FT-25 | Rich contact with Call/Message/Email actions, normal and narrow screen, long locale labels and large text. | Three actions share a row when measured labels fit; deliberate large-text reflow allowed; primary badges align with values. |
| FT-26 | On ordinary and diagnostic variants, generate/export/dismiss diagnostics after manual/native/local-change triggers and transient/terminal failures; rotate progress for a large import, restart, clear history and exercise picker cancel/storage error. | Schema 4 includes UTC/process-relative times, trigger/disposition, failure boundaries, bounded protocol codes, current grouped causes and explicit unavailable/truncated states. Error/transition retention survives repetitive progress; history stays distinct from current facts. No values/credentials/private IDs/raw errors/authentication traces; unknown fields remain unknown. Journal storage failure cannot change sync behavior; failed clearing never claims durable deletion. Clearing/dismissing leaves already exported documents intact. |

## Remote-deletion recovery

Use separate disposable contacts and compare exact values, preserved properties,
photo content and per-email group occurrences. Inventory omission and a generic
404 MUST NOT enable a destructive choice. With structured absence and a retained
local edit, exercise all three confirmed choices, cancellation without a choice,
restart and an owner change. Keeping/editing a draft MUST create no remote
mutation or new Android copy. The full local draft MUST remain available after
process restart and count toward sign-out protection with an empty outbox.

Recreate a rich contact with duplicate emails and distinct groups. Verify a new
canonical ID and UID, no reused service tokens/provider receipts, one durable
CREATE and eventual exact occurrence assignments. Lose the acknowledgement and
retry: reconcile the same identity, never create another destination. A source
that reappears before execution MUST stop the choice without a remote DELETE.

Change a native field before cleanup and between observation/deletion; no new
intent or foreign linked source may be lost. Inject storage failure before draft
commit and interruption after provider deletion: preserve the former work and
resume only a matching durable cleanup intent. Opaque Android-only payloads,
unknown groups and unsafe category mappings MUST block rather than be omitted.
Abandonment after confirmation must resume cleanup without resurrecting edits.
Check eight locales, narrow/large-text scrolling and explicitly record unexecuted
physical/Web/accessibility variants in the private QA result.

## Per-contact confirmation evidence

From a saved contact and a named Sync contact issue, open/close synchronization
details repeatedly, rotate and return. Compare the exact canonical revision,
payload and obligations before/after. Consultation MUST issue no remote GET,
provider query, mutation, acknowledgement or recovery request. Refresh MUST be
an explicit ordinary request through the same account runner. A retained deleted
contact MUST show its deletion summary without recreating a directory entry.

Compare Contako saving, Proton confirmation and Android verification separately:
an offline local note makes older Proton proof historical/pending; an independent
Proton conflict MUST NOT invalidate a still-current Android readback. Existing
legacy contacts MUST remain unknown when no sufficient proof exists. An ordinary
unchanged refresh need not manufacture a new proof or fetch all legacy cards.
Advance the provider epoch and verify that old Android evidence no longer
confirms the current context, without invalidating unrelated Proton evidence.

Include a canonical photo whose bytes are unavailable and hence omitted by the
mapper: a values-only readback MUST NOT confirm a complete Android copy. Compare
receipt-backed transformations separately from original canonical bytes. Check
local-only dates/phonetics and local labels on otherwise remote values without
calling them upload failures. Pending email-group assignments MUST stay separate
from contact-field confirmation. Owner cleanup MUST remove all panel identity
and reject a delayed old-owner observation. Exercise scrolling at 200% and keep
unexecuted TalkBack/physical fault cases explicit in the local result.

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
partial. Repeat for malformed supported content, separately
from account/epoch, identity, catalog and storage failures, which MUST stop safely.
Never use an aggregate display or a surviving foreign linked source as the oracle.

Separately repeat the native edit with unmapped MIME rows containing opaque text,
BLOBs and arbitrary SYNC claims. Known fields MUST still converge; the opaque
payload MUST remain unchanged and Android-only. Replace an attached known EMAIL
row's MIME with an unknown one without changing its locator: this identity
inconsistency MUST retain the canonical email, not infer its deletion. New opaque
rows with spoofed claims but no matching durable binding MUST NOT block mapping.

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

For FT-12P, repeat a native photo replacement with the exact Samsung
`vnd.sec.cursor.item/profile_card` row present. The standard photo MUST be captured
and uploaded while the opaque row remains unchanged. An unrelated edit, restart
and no-change pass MUST preserve it; a concurrent opaque-row change MUST still
invalidate a stale write. Do not infer Proton photo delivery from a local preview.

Also replace a photo through an editor that recreates its standard row without
Contako's value/order markers. After durable native ingestion, projection MUST
restore missing markers only through the exact owned row's durable binding and
current write authorization. A stale/rebound row or a nonempty conflicting marker
MUST NOT be claimed. Verify completion, restart and a no-change pass without
repeated photo writes.

For FT-26, generating a report MUST leave the technical text collapsed until the
user requests it. Show/hide MUST NOT regenerate, clear or export the history;
export and deletion remain explicit actions.

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

- Revoke an active session while changes are pending, then sign in as the same
  owner without discarding data. A different owner MUST NOT see or upload the
  retained partition. Repeat after restart and after Android provisioning fails.
- On an empty installation, cancel a first login at TOTP, then use another
  authorized account. Repeat after an unsupported authentication method. No
  abandoned initial reservation may block the second login; an established
  owner's retained data MUST remain protected when reauthentication is cancelled.
- Interrupt a create after Proton accepts it and before its local acknowledgement.
  Resume ordinary sync and full repair separately. Preserve one local identity
  and the durable edit, with no duplicate import; resolve any explicit conflict
  through the normal guarded choice.
- Where the remote service retains the input, import two distinct contacts sharing
  a vCard UID. Edit one, then delete the other. Their remote/local identities and
  owned Android copies MUST remain distinct. If the service rewrites or merges
  the input, record that variant as unavailable rather than qualifying UID handling.
- After a platform account-creation refusal, return to the foreground with the
  same permission state. Verify that the retained binding permits account
  creation and projection without restarting the complete Proton import.
- Edit native phonetic given/middle/family components, then change only a Proton
  note. Repeat with a family-only phonetic name and an explicit Proton conflict
  choice. Exact local components and identities MUST survive adoption, restart
  and a no-change pass; opaque imported extensions remain independently preserved.
- Add a local custom date and unsupported labels to distinct URL/relation/address
  values, then change only a Web note. Repeat using the explicit Proton conflict
  choice. Proven local complements MUST survive; a remotely deleted value MUST
  remain deleted. Compare the local/owned Android representation separately from
  fields the Web client can display.
- Include a single unreadable remote card among healthy cards. Healthy cards
  MUST reach Room and Android; the failed import MUST remain visible and retry
  without rehydrating all unchanged healthy cards. A later repair or deletion
  MUST resolve only the corresponding obligation.
- Change the preferred email in the native editor while its two email
  occurrences have different groups. Check both memberships independently on
  Proton, repeat a second edit and restart. A preference change MUST NOT copy
  one email's entire group set to another.
- Temporarily deny or fail group-catalog access. Independent contacts MUST
  continue; no group may be deleted from an unavailable catalog. Restore access,
  refresh and verify that retained group intent resumes without reinstallation.
  While a group is partially delivered, remove a delivered member and add another
  on Proton Web. Reopen Contako, correct only the deferred invalid member and
  verify that the remote removal/addition survives. Then explicitly edit group
  membership and remove a deferred link: that new local choice MUST supersede
  its old retained scope. The sanitized report MUST list the partial group
  separately from the invalid-email cause without revealing either identity.
  Before any full-card refresh, edit only a delivered member's phone and sync
  twice: its still-present membership MUST survive the contact PUT. Repeat with
  an already hydrated clear category after removing that member on Web; the
  old category MUST NOT re-add it. Preserve Web additions, unrelated categories
  and other email occurrences. Exercise lost PUT acknowledgement, identity
  replacement, explicit Proton choice and a permanent assignment refusal;
  restoration MUST remain exact and retry history MUST remain unchanged.
  Separately characterize an acknowledged contact PUT whose group restoration
  is blocked: remove that member on Web during the wait, restart and resume.
  Compare exact per-email memberships. The current restoration can reapply that
  removal; record this as the known concurrency limitation, not a passed
  concurrent-removal guarantee inferred from the nominal cases above.
  For an unverifiable legacy partial scope, use the issue's Review group action.
  It MUST open the affected current group without saving or scheduling writes.
  Only explicit Edit/Save MAY replace that scope. A stale/deleted/foreign group
  MUST NOT be opened as the current account's editable item.
  Include a contact imported during the outage with per-email group references:
  known missing memberships MUST recover through verified hydration, without
  overwriting pending assignments. A no-change pass MUST NOT repeat that hydration.

- Change only a Web note/photo/unknown field, then another local field; public
  directory equality MUST NOT permit a stale upload. Both snapshots survive restart.
- Choose Contako and Proton separately. No second choice while an upload is in
  progress; network failure retains intent; a newer local/remote edit requires review.
  Open comparison with structured names and preserved read-only fields; every
  canonical field type MUST have a readable label without crashing.
- Ordinary contact fields and images MUST have no repeated compatibility captions,
  notices or help buttons. Open the single Information entry at the bottom of
  detail/editor; inspect shared, Proton-only, Android-only, local and preserved
  occurrences when present. Shared fields start collapsed. Unknown support MUST
  remain unknown, local labels MUST NOT demote an otherwise transferable value,
  and source-card backups MUST NOT appear as extra properties. Preserved names
  and values remain selectable here. Check narrow layout, dark theme and 200% fonts.
- Open contextual field help in the picker (icon only), through Information on
  an edited occurrence and on a read-only field. Closing/Back/rotation MUST preserve the draft, validation and
  picker position without Save, gateway/provider access or new sync work. Check
  custom dates and complete phonetics, secondary notes/photos, URL value versus
  local label, and preferred/secondary email groups. A successful group LIST MUST
  leave unknown ASSIGN_EMAILS capability unknown. Explicit assignment success,
  refusal and session invalidation MUST update only the corresponding help fact.
  Denied Contacts permission suspends copying without changing format support.
  Distinguish editor visibility and actual peer confirmation from these rules.
- For an imported URL, relation and address occurrence, leave the imported label
  unchanged, then edit it to a custom label without Save. Help MUST identify the
  local-only label in Information while keeping the value's Proton support separate.
  Revert to the original label before Save: the draft-only label indication MUST disappear.
  Standard address type changes MUST remain transferable. New and already-local
  labels remain controls. Opening/closing help MUST preserve imported provenance,
  the original, draft, revisions and pending work without peer I/O.
- Compare differences first, then reveal matching fields; close without changes.
  Use repeated emails with distinct group assignments and reordered positional
  keys: unproved matches MUST remain separate. Check labels, preference, components
  and order, photo versus logo, long text and 200% scaling. Referenced media MUST
  NOT be downloaded. Review both whole-contact consequence previews before final
  confirmation. Verify retained local dates/phonetics/eligible labels against the
  resulting canonical contact, and keep raw source cards out of the field list.
  While the preview is open, change a revision or group dependency; the old choice
  MUST NOT authorize stale application. A queued choice stays explicitly pending;
  inspect actual peer confirmation in the contact synchronization panel.
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
  Refusal before cleanup starts MUST permit Cancel and Sync without requiring
  discard. A partial cleanup MUST retain exclusive recovery after restart. Open
  a draft before logout, then use another authorized fixture account: prior-owner
  contact/group drafts and delayed saves MUST neither appear nor be committed.
- For FT-01/FT-17 authentication recovery: retain a blocked group or validation intent,
  then require session renewal or interactive key unlock. The reconnect action
  MUST remain visible, and resolving it MUST preserve the original pending work.
  Verify immediate notification eligibility even if the earlier non-authentication
  block already produced a notification; subsequent duplicate alerts remain suppressed.
- For FT-26 terminal diagnostics: exercise an authorized failure with only a generic
  final outcome and no detailed failure event. Rotate the journal and restart.
  The retained record MUST still identify the last work boundary; a later generic
  banner MUST NOT hide a detailed failure recorded for the relevant pass.
- For FT-15/FT-07 composite organization rows: save a title without a company,
  then separately replace the title, add/remove a role and add/remove a company.
  After a Proton round trip, edit Company and Department separately. Verify
  the ORG units, generated FN and Android company/department independently;
  preserve further imported units and literal slashes in an edited company.
  Clearing Department MUST NOT remove Company or those further units.
  Add, remove and replace TITLE/ROLE companions on an existing provider row,
  and repeat with linked phonetic-name components. The durable companion ledger
  MUST follow only an exact prepared, verified write. Interrupt before the write
  and after provider commit, then resume; forged claims, stale revisions and
  concurrent native edits MUST NOT authorize adopting a different binding.
  Receive a verified Proton echo that assigns new companion IDs after an earlier
  write lost completion. Only a complete retained commitment may recover that
  older provider baseline; the current canonical version and pending intentions
  MUST remain unchanged until their own newly verified projection.
  Include a second save before the first projection completes. Verify exact
  values and durable linked identities after each save in all peers, restart and
  repeat a no-change pass. No divergent binding, pending Android copy or false
  acknowledgement may remain; ownership/version guards MUST stay effective.

## Recovery and partial-delivery acceptance

- During uncertain CREATE recovery, inject 429 into each inventory/card boundary,
  including a deletion of that uncertain creation. Restart before the retry.
  Preserve ambiguity and server deadlines; never POST twice or acknowledge a
  deletion without absence/identity proof. Also prove absence first, then reject
  a new POST with 429: a captured older command MUST NOT restore obsolete ambiguity.
- With many uncertain creations, bound full-card scans per pass. Crash after
  preparing the first but before claiming another; introduce the second UID on
  Proton, then resume. Only unchanged canonical/payload/outbox snapshots captured
  before a complete stable scan may receive its absence proof (at most 25).
  New or changed intentions MUST retain ambiguity; waiting for the
  scan budget MUST NOT spend automatic failure attempts. A verified identical
  creation converges without a second POST or a version-choice prompt. Duplicate
  UIDs, private-field differences and lost opaque properties MUST remain uncertain.
  Place an unreadable card before a later known remote identity: continue reading
  and recover that bound identity. An unbound visible UID candidate with incomplete
  coverage MUST NOT be adopted as unique or offered a blind creation. Exhaust
  that visible-match verification: the affected item identifies the unreadable
  dependency and offers a cooled verification retry, not a generic conflict or
  fabricated version comparison. Retry while still unreadable, restart, then make
  the dependency readable: a complete equivalent match converges without POST;
  truly different content retains a real conflict. A known bound identity whose
  card is unreadable also remains eligible for verification rather than creation.
  Exhaust incomplete verification without a visible match: show **Create anyway** with a
  duplicate warning; cancel has no effect, confirmation survives restart and server
  delay, and a local edit invalidates it. Never create without that confirmation.
  Repeat with 25 ambiguous creations and one unreadable unrelated card. A stable
  scan consumes one verification attempt per unchanged due target, reaching the
  existing retry limit together rather than rescanning separately for each target.
  The claimed target is charged once; future, blocked, changed-payload/revision
  and newly added intentions are untouched. Abort before the second inventory
  with a network/429 error: no distributed attempt consumption is permitted.
- On a large first import, fail a card transiently late in acquisition, then retry.
  Healthy committed cards MUST remain checkpointed; failed/unread cards become
  durable obligations before cursor acknowledgement. Respect Retry-After across
  restart/manual/targeted requests. Isolate only the specified per-contact remap
  failure; a real storage/ownership failure stops unsafe work. Cancel during both
  canonical and checkpoint transactions without reporting a storage fault.
- Exhaust a transient contact/group upload and a deletion. The identified Sync
  item offers one explicit cooled retry; repeated taps do not reset attempts,
  identity, ambiguity or deadlines. A separately blocked item MUST NOT suppress
  healthy eligible work in later bounded batches or transient framework scheduling.
  Advance the controlled clock across 24 hours and restart: one automatic probe
  may run, not another immediate attempt; the next day's probe keeps the original
  history and ambiguity. A longer server minimum wins. Authentication, validation,
  crypto, real conflicts and incomplete creation verification stay excluded.
  In a large import, simulate a sustained outage: at most the current hydration
  window finishes after three consecutive transport failures; unread cards get
  deadlines without spent attempts. A single timeout or malformed card still
  allows healthy work. Include a 429 in the same window and retain its longer delay.
- Edit one email to an invalid value while changing a phone/note. Verify the
  other fields on Proton, preserve the previous accepted email and every group,
  and retain the invalid input and partial state locally after restart/no-change.
  Repeat with a new invalid email, repeated equal addresses with distinct
  preferences/groups, correction to a valid address, deletion and explicit Proton
  conflict choice. Sign-out MUST account for retained partial intent.
  Reorder or remove one email on Proton so a different occurrence takes its old
  vCard line position: never replace that other email or inherit its groups.
  Change remote groups while the invalid input is retained; repeat hydration
  without restoring old groups. Also edit during an in-flight upload, then receive
  its older acknowledgement with new email identities. Only proven identities
  transfer; the newer content remains pending. A remote whole-contact deletion
  MUST retain an accessible draft and require an explicit recovery choice.
  Put a newly omitted invalid email in a group alongside valid members, remove
  one old valid member, restart and retry. Valid membership changes reach Proton;
  the invalid occurrence and group intent remain durable without a false conflict
  or full acknowledgement. Once accepted members are delivered, ordinary passes
  and restart MUST show partial synchronization with zero queued writes, no I/O
  failure and no repeated membership reads for unchanged input. Sign-out still
  protects that intent. A remote member identity change reactivates guarded
  preparation; a remote group rename/deletion stays safely reconciled.
  Correction resumes the remaining membership. Repeat
  with identical emails, different per-occurrence groups and a lost contact CREATE
  acknowledgement; compare the verified submitted projection, not the invalid input.
- Create organization-, nickname-, phonetic-, phone- and email-only contacts.
  Verify the generated display name without fabricated structured parts; change
  its source after a Proton round trip and restart, then set an explicit name.
  Verify actual Proton CREATE and UPDATE acceptance: generated-name metadata
  belongs in the encrypted and signed private card, bound by UID to the unique
  authenticated public FN. Do not invent an empty N or a private FN. Preserve an
  imported empty N. Migrate recognized legacy public markers on a compatible
  write; unsigned, altered or unbound marker data MUST NOT replace an explicit
  name. A later Proton Web FN edit MUST take precedence over an old marker.
  If this contact is blocked, Sync MUST identify it by its resolved name and
  offer the applicable contact action; a blank stored alias is not its label.
- Keep an unrelated contact blocked while changing a group, including removed
  email edges, an empty group and repeated addresses. Only proved dependencies
  may defer that group; legacy incomplete catalogs require verified membership
  acquisition. Lost acknowledgement must retain removed edges until exact proof.
- Exercise each allowed Android formatting equivalence after an owned write,
  then perform a genuine native edit using the real saved provider baseline.
  Reject concurrent version changes, changed preferred email, missing/truncated
  values and altered dates. Initial/legacy copies without write evidence remain
  strict; no provider-generated normalization may be uploaded as an edit.
- For an invalid signature/decryption, show the exact closed failure category and
  offer recheck or explicit ignore of that obligation generation. A later private-
  only remote event reopens ignored work. No unverified content may be edited,
  re-signed, treated as canonical or projected as an effect of these actions.
