# Agent working method

## Entry and roles

Read root [instructions](../AGENTS.md), optional private `AGENTS.local.md`,
[Specification](SPECIFICATION.md) and [Known limitations](KNOWN_LIMITATIONS.md).
Load only the contracts and skills needed for the assigned task. Existing product
decisions and the latest user scope govern work; agent setup does not start a
feature, change a synchronization contract or authorize live testing.

| Task | Agent | Relevant workflow or inputs | Deliverable |
| --- | --- | --- | --- |
| Specify | `contako_specifier` | Specification, affected product/field contracts, Decisions | Observable requirements and acceptance drafts |
| Plan | `contako_planner` | Architecture, synchronization/state contracts; code-review skill when relevant | Small end-to-end slices with ownership and verification |
| Research | `contako_researcher` | Dependencies, version catalog, maintained primary sources | Pinned capabilities, source evidence and reuse limits |
| Develop | `contako_developer` | Development; sync-investigation and Android-validation skills as relevant | One authorized behavior change and focused evidence |
| Test | `contako_tester` | QA, relevant cases, Android-validation; sync-investigation when relevant | Assigned regressions/fixtures and actual results |
| Review | `contako_reviewer` | Code-review skill and affected contracts | Independent read-only findings with trigger and impact |

Definitions live in [the project agent directory](../.codex/agents/). These are
selective roles, not six obligatory workers. The primary remains responsible for
the outcome and shared coordination. Research/review are read-only; the primary
can persist their evidence. Testers do not patch production code to make tests pass.

## From request to verified change

Recover accepted behavior from the request, [Decisions](DECISIONS.md) and the
affected specification. Separate proposals and assumptions from requirements.
Small edits do not need a new specification, milestone or approval cycle.

Plan backward from observable acceptance, across the minimum necessary boundaries.
Name inputs, ownership, durable transactions, dependencies, cancellation/retry
behavior, expected side effects and verification. Keep Room canonical locally,
Proton as the remote peer and Android as an editable projection. Preserve durable
intent before upload, one serialized account-scoped engine, unknown vCard data,
and provider ownership/epoch/version/write-receipt guards. A new interface must
define behavior and failure semantics, not only signatures.

For defects, follow the first failing boundary and a falsifiable explanation.
Change one causal behavior at a time; select a meaningful regression and the
appropriate integration journey. Use [Development](DEVELOPMENT.md) and
[app commands](../app/README.md) rather than inventing build tasks. Inspect exact
pinned Proton/Android APIs when uncertain; do not run upstream installers or
import unrelated runtime frameworks as part of agent preparation.

## Delegation and handoff

Use bounded delegation for substantial independent research, planning or review
when useful within the authorized task. The primary assigns each child:

- a question or accepted slice and relevant input paths;
- a concrete deliverable and exclusive writable paths, or read-only scope;
- expected evidence, side-effect limits and unresolved dependencies.

At most three children run concurrently; no grandchildren. Inherit model/settings
unless the user asks otherwise. Independent research/review may run in parallel.
Settle shared contracts first and use one active writer per file. Public contract
edits and writers with overlapping scopes run sequentially. Do not create sidebar
chats or external issues as an incidental delegation mechanism.

Children return changed files, findings, actual commands/results and limitations.
The primary examines the actual artifacts, resolves disagreements and integrates
the result. Only the primary updates public implementation limits after checking
the evidence. Sequential role-based self-review is valid when delegation is
unavailable or unnecessary; identify it honestly rather than claiming independence.

Keep temporary drafts, plans and handoffs under ignored `.agent-local/`. Keep QA
runs/raw evidence in ignored `app/build/` as required by [QA](QA.md). Public files
record reusable contracts and product limits, not task transcripts or private
operator context. Existing unrelated changes must survive every role's work.

## Evidence and authority

Select checks for the actual claim: structural instruction checks, JVM behavior,
Room/provider integration, minified Android behavior, or authorized physical
device and Proton journeys. Compare both intended and untouched fields in
applicable peers. Display aggregates, thumbnails, zero counters and successful
compilation do not prove field equality or safe baseline adoption.

Use the [QA result template](testing/RESULT_TEMPLATE.md) for actual QA runs.
Distinguish PASS, FAIL, NOT RUN, OWNER-VALIDATED and KNOWN LIMITATION with the actual
build/scope. A mock does not qualify Proton; a debug build does not qualify
minification; software checks do not qualify a physical OEM. Instruction-only
changes need syntax, paths, routing and ignore checks, not an APK or session reset.

Device/account operations require applicable Contako authorization and controlled
fixture/cleanup scope. Reference projects, roles and test plans grant no standing
authority. Never log credentials, contact payloads or private identifiers, and
never reset a connected session merely to repeat setup.

## Codex loading

[Agent setup](AGENT_SETUP.md) describes project configuration, skill discovery and
privacy. Roles use standalone `.codex/agents/*.toml`; existing skills remain under
`.agents/skills/`. Native discovery must be checked in a trusted session rooted
in Contako. Structural validation alone does not prove role loading or behavior.
No global installation or model change is needed for this setup.
