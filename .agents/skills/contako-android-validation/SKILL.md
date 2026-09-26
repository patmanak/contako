---
name: contako-android-validation
description: Select and execute scoped Android validation for Contako Compose UI, ContactsProvider, lifecycle, background work and minified APK changes. Separate build evidence from real device and Proton acceptance.
---

# Validate an Android change

Read the repository AGENTS.md and optional AGENTS.local.md. Paths below are
repository-relative. Use `app/README.md` for commands and
`docs/FUNCTIONAL_TEST_PLAN.md` for cases; local device restrictions and permissions
are not granted by this skill. Select the connected target at execution time and
keep its identifier private. Do not reset an authenticated session as routine setup.

## Choose checks by risk

| Change | Useful checks |
| --- | --- |
| Pure policy or durable state transition | Focused JVM regression for the meaningful input/outcome; failure/cancellation where affected. |
| Provider or Room boundary | Compile relevant instrumentation; execute selected account-owned cases with real provider/transaction behavior when authorized. |
| Compose, navigation or forms | Changed-path build plus actual touch/IME/back behavior, scroll/draft restoration, font scaling and accessibility relevant to the change. |
| Serialization, crypto, reflection or resource shrinking | A minified build and affected physical parse/edit/sync path; debug success alone is insufficient. |
| Scheduling, cancellation, performance or battery | Known build and comparable before/after conditions; distinguish forced work from natural scheduling and USB charging from battery use. |
| Documentation/instructions only | Links, consistency, discovery/ignore checks. No APK build, session reset or synthetic contact campaign. |

Use the existing wrapper, explicit test build type, package suffix and ABI for
the intended artifact. Do not guess Gradle tasks or change the dependency graph
to make validation convenient. Read the selected instrumentation class before
running it; some probes mutate account/provider state. Do not run the entire
instrumented suite without reviewing that scope.

## Respect Android-specific evidence

- An aggregate ContactsProvider display name is not a source-owned raw-row value.
  Inspect only authorized rows and preserve other accounts when testing aggregation.
- Field equality and byte/identity proof are different checks. A thumbnail or a
  changed file locator does not prove full photo delivery, loss or safe rebinding.
- Saved Compose state, activity recreation, process death and device reboot are
  distinct scenarios. Run the ones affected; do not label one as all four.
- A permission denial or lifecycle cancellation MUST retain recoverable intent.
  A callback or claimed notification is not proof of system delivery.
- Measure a performance claim with equivalent fixture/load, cache, network and
  power conditions. Do not speed up sync by dropping verification or raising
  concurrency caps without evidence. Honor local debugger/profiler permissions.

For an installation, verify package/signature compatibility and preserve data.
Record the actual installed artifact identity, debug/minification/diagnostic
flags and exact tested scope. For a contact journey, compare both changed and
unchanged values in Contako, Proton and the system editor as applicable.
Report PASS, FAIL, NOT RUN or OWNER-VALIDATED without promoting compilation or
instrumentation-only evidence into a real multi-peer pass. Use the ignored local
result template from `docs/QA.md`; maintain known limitations without task logs and change reusable
functional cases only when acceptance changes. Keep raw captures ignored.
