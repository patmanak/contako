# Repository history maintenance

History replacement is a separate, explicitly authorized operation. Ordinary
documentation cleanup and local commits MUST NOT push, force-rewrite remote
references, delete tags/releases or modify linked worktrees.

## Recovery

.repository-backups/ contains ignored recovery material. Build/cleanup operations
MUST NOT delete or publish it. Before a history replacement, retain a verified Git
bundle and source snapshot independently of the checkout, including all references
needed for recovery. Record exact paths, hashes and restore checks locally, not
as a public task transcript. Existing archives MUST NOT be assumed current.

## Tree and publication boundaries

- Review the exact proposed source tree and preserve complete licenses, third-party
  notices, artwork attribution, dependency verification and Room schema history.
- Exclude credentials, signing keys, personal contact/device data, raw diagnostics,
  temporary reports and private operator/agent notes.
- Validate affected code and documentation; identify the artifact and source used
  for release qualification. A new root commit is not product acceptance.
- Choose source, signing/channel identity and release description explicitly.
  Do not relabel an older APK as built from a new source history.

## Remote references

A publication branch MAY start from a reviewed source tree with a new root commit,
while the development checkout and its references remain private. Verify that the
candidate has no parent and that its tree differs from the reviewed source only
by the intended changes. Preserve the approved public author identity, licenses,
source attribution, dependency metadata and Room schemas.

Later changes MUST be reviewed and applied without merging the retired ancestry.
Publish only explicitly selected clean references; do not push all branches/tags
or copy private recovery material. A clean branch does not make the other local
references safe for publication. Keep snapshot identities and excluded in-progress
work in the ignored local result, not in a public maintenance transcript.

Re-read branch/tag references and GitHub releases/assets immediately before any
approved mutation. A concurrent change MUST stop replacement rather than be
silently overwritten. Review main, tags, release assets, branch protection and
linked worktrees separately; do not use a blanket mirror push.

Replacing main does not remove history reachable through tags, pull-request
references, forks, caches, releases or independent copies. Housekeeping MUST NOT
claim to erase every historical disclosure. Keep sensitive recovery inventory
and execution logs local.

See [Licensing](LICENSING.md), [QA](QA.md) and
[known limitations](KNOWN_LIMITATIONS.md) before public distribution.
