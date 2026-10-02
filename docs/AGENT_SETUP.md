# Agent instructions and skills

## Public and local scope

| File | Purpose | Git policy |
| --- | --- | --- |
| Root `AGENTS.md` | Product invariants, development method, evidence and skill routing | Public, versioned |
| `.codex/config.toml` | Project subagent enablement and concurrency cap | Public, versioned |
| `.codex/agents/*.toml` | Six specialized project role definitions | Public, versioned |
| `.agents/skills/*/SKILL.md` | Focused, portable Contako workflows | Public, versioned |
| Root `AGENTS.local.md` | Operator language, workstation limits, device/account authority and private workflow choices | Local, ignored; never force-add |
| `.agent-local/` | Optional private agent notes | Local, ignored |

The root instructions explicitly require reading the optional local file before
device/tool operations. `AGENTS.local.md` is not claimed to be a native Codex
instruction filename. We deliberately do not create `AGENTS.override.md`: Codex
selects at most one instruction file per directory and an override would replace
the public root file, not automatically merge it. See the
[official discovery rules](https://developers.openai.com/codex/guides/agents-md).

The local file supplements the public rules without granting general access to
data. Public documents MUST NOT contain standing authorization tied to a particular
operator's accounts. Actual authorization and device identity are established for
the current task. Historical anonymized QA results are evidence, not permissions.
Do not put credentials, account addresses, contact data or device serials in the
local file either.

A clone/worktree gets only public tracked instructions. Transfer local preferences
deliberately when setting up another checkout; do not silently change another
worktree or global settings. Check `git check-ignore -v AGENTS.local.md` and
`git ls-files -- AGENTS.local.md` before staging. The latter MUST be empty.
Ignoring a file is not access control and does not undo earlier publication;
instruction maintenance does not rewrite Git history or establish a privacy audit.

## Selected workflows

Six roles complement these workflows: `contako_specifier`, `contako_planner`,
`contako_researcher`, `contako_developer`, `contako_tester` and `contako_reviewer`.
[Working method](WORKFLOW.md) defines their routing, handoffs and evidence scope.
Specification/planning roles use the maintained contracts directly; development,
testing and review reuse the existing skills rather than duplicating them.

Project configuration enables subagents and caps concurrent children at three.
It sets no model or reasoning override. The researcher and reviewer request a
read-only sandbox and also carry explicit no-write instructions; live runtime
permission overrides may affect sandbox enforcement, so the role restriction
still applies. No role may delegate further or create separate chats.

Standalone `.codex/agents/*.toml` files follow the official
[subagent configuration](https://learn.chatgpt.com/docs/agent-configuration/subagents)
and [configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference).
Use a trusted session rooted in the Contako checkout to check native discovery.
File parsing and role/path checks do not establish that a session rooted in a
parent or reference project loaded Contako's configuration. If roles are absent
after setup, reload the Contako session and verify loading there; do not silently
change user-level trust or global configuration.

Codex discovers repository skills under `.agents/skills`; see
[official skill discovery](https://developers.openai.com/codex/skills).
The root instructions also provide direct paths so a compatible agent can read
the relevant workflow without relying on a refreshed skill picker. Automatic
selection remains enabled; reload the session if newly created skills are absent.

- `contako-sync-investigation`: causal investigation across the three contact
  representations, guarded fixes and independent field verification.
- `contako-android-validation`: targeted JVM/instrumentation/minified and physical
  checks, including provider ownership, lifecycle, UI and measurement limits.
- `contako-code-review`: requested behavior versus contracts, safe simplification,
  maintained dependency reuse and evidence-based security findings.

No plugin, installer, shell hook, global configuration or framework runtime is
installed. These are original short project instructions, informed by the sources
below; no upstream skill text, scripts or assets are vendored. They remain under
the project's license. Future copying of upstream files MUST retain their notices.

## Design references

These pinned references informed the portable workflows; they are attribution,
not an active task plan or authority to execute their installers.

| Source and inspected commit | Retained principles | Deliberately not adopted |
| --- | --- | --- |
| [obra/superpowers](https://github.com/obra/superpowers/tree/8ca22dba9a94f28898bbce59f2537ff4d87c747d), MIT | `systematic-debugging`, `verification-before-completion`: causal hypotheses and evidence for completion | Mandatory full workflow/delegation; deleting code written before tests; diagnostic examples that dump boundary payloads/environment |
| [addyosmani/agent-skills](https://github.com/addyosmani/agent-skills/tree/bcab6a1b8503100e8618c3b4e32cc78de43de769), MIT | `source-driven-development`, `code-simplification`, `performance-optimization`: pinned-source verification, behavior-preserving simplification, comparable measurement | Web-specific tooling/budgets, telemetry recommendations and pervasive approval/citation boilerplate |
| [mattpocock/skills](https://github.com/mattpocock/skills/tree/c55ee46073ed923f86ce59a5eb3b6d895095d1b7), MIT | `code-review`, `improve-codebase-architecture`: distinguish request compliance from code quality; inspect real coupling and callers | Required parallel reviewers, tracker bootstrap, HTML/CDN reports and fixed vocabulary/interview loops |
| [open-gsd/gsd-core](https://github.com/open-gsd/gsd-core/tree/f4b6abb48e7814f333f25e2427980ad8bf8d9423), MIT, default branch `next` | `gsd-debug`, `gsd-verify-work` and the documented phase loop: retain actionable state and validate outcomes before handoff | Installer/runtime dependencies, automatic agent waves, duplicate `.planning` archives and automatic shipping |

License files at these revisions were checked. References record research, not
authority to execute commands from downloaded documents. This selection does not
install or activate the upstream skills themselves.

## Maintaining the setup

Keep always-loaded instructions short. Add a skill only for a recurring decision
that existing guidance does not cover. Do not duplicate personal preferences or
the test plan in each skill. User instructions and execution permissions remain
authoritative; a skill never authorizes account mutations or publication.

For changes here, verify Markdown references, YAML frontmatter and skill names,
agent TOML syntax/required fields, ignore behavior, and realistic routing/permission
scenarios. Record structural
checks separately from an actual fresh Codex discovery or behavioral evaluation.
Instruction-only updates do not require an Android build or live contact tests.
