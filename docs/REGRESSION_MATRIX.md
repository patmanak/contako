# Regression coverage

This is traceability, not a campaign report. Every row retains a previously
reported problem or a current verification gap. IDs F/A are aliases for existing
defects, not obsolete execution gates. PASS belongs to a specific run/build;
a listed test is not a claim that its entire case already passes.

| Reported problem | Dataset / required state | Unit | Software | Target |
| --- | --- | --- | --- | --- |
| F-01 Background timing/reboot | C01; screen off, reboot/first unlock, network return | UT-08 | SW-09/10 | FT-10/11 |
| F-02 Bulk/partial Android projection | load-300/load-5000; bounded interruption | UT-07/08 | SW-02/07/09 | FT-12/20/22 |
| F-03 Concurrent edit/delete, resurrection | C01/C03; offline opposing peers | UT-08/09 | SW-01/02 | FT-13/14 |
| F-04 Real keyboard input loss suspicion | C12; type/paste, no ADB text injection oracle | UT-01 | SW-11 | FT-16 |
| F-05 Narrow actions, badge alignment, accessibility | C03/C12; large text, landscape, other OEM | UT-02 | SW-11 | FT-19/25 |
| F-06 Group availability/custom fields | C03/C04/C08; unavailable groups if account available | UT-03/12 | SW-03/04 | FT-05/18 |
| F-07 Artwork theme/insets/provenance | no contact; login/About/launcher | UT-12 | SW-11 | FT-19 |
| F-08 Display-only projection stuck | C02; generated Android name parts | UT-01/07 | SW-06 | FT-03/24 |
| F-09 Structured-name changes lost | C03/C04; all five N components | UT-03 | SW-04 | FT-04/24 |
| Canonical names depend on card order (new isolated finding, live impact unverified) | C01/C03/C04; public FN before private N | UT-03 | SW-04 | FT-24 |
| F-10 Native phone after deleted group | C03/G01; completed group-deletion receipt | UT-07 | SW-03 | FT-06/07 |
| F-11 Zero-outbox incomplete pass | C01; retry without queued mutation | UT-08/11 | SW-09/11 | FT-17 |
| F-12 Pending Android copies despite visible contact | C02/C09/C10; full/thumbnail, baseline and date variants | UT-05/07 | SW-06/07 | FT-08/17/26 |
| F-13 Note upload rejects imported public provenance | C05/C06; clear EMAIL and encrypted note | UT-03/04 | SW-04/05 | FT-04/09 |
| F-14 Attention count/list mismatch, empty list or ordinary initial copies shown as errors | C03; pending initial copies, blocked outbox/projection, excluding search filter and recovery | UT-11 | SW-11 | FT-17/26 |
| F-15 Web equivalent PREF/VALUE rejected | C07/N01/N02; valid repeats vs conflicting parameters | UT-04 | SW-04/05 | FT-09 |
| F-16 Photo then combined Web edit/name/date relocation | C04/C10; A/B/C, unchanged birthday, pending batch | UT-03/07 | SW-07 | FT-08/09/20 |
| F-17 Creation uploaded but no Android ledger | C01/C02; no existing provider copy | UT-08 | SW-01/03 | FT-03 |
| F-18 Yearless birthday removed by Web edit | C08 plus full-date C04 control | UT-05 | SW-04 | FT-04/09 |
| F-19 Native note/group revision rollback | C03; separate memberships on both emails | UT-07/08 | SW-03 | FT-05/07 |
| F-20 Native dirty row missed with empty outbox | C03; recent prior success, auto-sync enabled/disabled | UT-08 | SW-09 | FT-07/10 |
| F-21 Native given-name edit overwrites alias | C03; explicit alias vs generated concatenation | UT-01/07 | SW-06 | FT-24 |
| F-22 Framework interruption crash/battery warning | C01/load-300; cancelled wait, locked unplugged idle | UT-08 | SW-09 | FT-11/20 |
| Samsung RCS and other opaque MIME rows | C03; own/foreign RCS, clean/dirty, unknown lookalike | UT-07 | SW-06 | FT-07/17/24 |
| Samsung unmatched initial name baseline | C03/C14; separate/aggregated sources and first photo | UT-07 | SW-06/07 | FT-08/24/26 |
| Legacy binary PHOTO/LOGO invisible | C10; explicit binary declaration, replacement MIME | UT-06 | SW-04/07 | FT-08 |
| Repair final context stale / no resume action | C04; hydration advances account state | UT-08 | SW-09/11 | FT-20 |
| A-01 Sign-out loses native edits/stale warning | C03; provider-only work, Cancel then convergence | UT-10 | SW-08/11 | FT-23 |
| A-02 Missing remote timestamp / clock uncertainty | C01; both edit orders, expired/untrusted clock | UT-09 | SW-01/02 | FT-14 |
| A-03/A-04/S-01 HTTP/plaintext/native bounds | N03 + generated bounded negatives; isolated runtime only | UT-04/12 | SW-05/12 | FT-26 |
| A-05 Notification delivery not wired | C01; auth and prolonged block, permission denied | UT-11 | SW-09/11 | FT-17 |
| A-07 CAPTCHA WebView renderer recovery | no contact; isolated auth session | UT-12 | SW-12 | FT-01 |
| Diagnostic build suffix rejected on export | no contact; ordinary/diagnostic version suffixes | UT-11 | SW-11 | FT-26 |
| Email removal/replacement Save and lost ack | C03/C04; one email removed, another edited | UT-01/03 | SW-01/04 | FT-04/05 |
| Blank display name, scroll return, search, single-image gallery | C01/C02/C03/C10; unchanged vs filtered list | UT-01/02/06 | SW-11 | FT-02/08/24 |
| Unsigned import vs invalid signature | C06; actual maintained crypto card type | UT-03/04 | SW-05 | FT-09/26 |
| Private-only remote changes | C04; keep public names/email/groups unchanged | UT-03/08 | SW-02/04 | FT-09/21 |
| Minification/registry and repeated photos | C04/C10; minified installed artifact | UT-03/06/12 | SW-04/05/12 | FT-08/26 |

Cases are defined in [unit](UNIT_TEST_PLAN.md), [software](SOFTWARE_TEST_PLAN.md)
and [target](FUNCTIONAL_TEST_PLAN.md) plans. Profiles and group membership oracles
are defined in [the dataset](../app/qa/dataset/README.md). Unknown personal input
syntax MUST NOT be presented as reproduced merely because a synthetic variant
passes. Deferred non-blocking issues remain covered; no cleanup silently marks
them fixed or makes all existing maturity evidence disappear.
