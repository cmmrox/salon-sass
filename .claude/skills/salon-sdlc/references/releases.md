# Releases

How a release is versioned, assembled and reported. The steps are the `release-prepare`,
`release-verify` and `release-cut` commands in [lifecycle.md](lifecycle.md). This product's
environments, what a test may touch, and the rules it imposes on a release are in
`docs/operations/environments-and-releases.md`; read that before touching anything that is not
local.

## Versions

Semantic Versioning, `vMAJOR.MINOR.PATCH`.

| Change in the release | Bump |
| --- | --- |
| Only defect fixes, no new behaviour | PATCH |
| At least one new feature, and everything existing still works for existing clients | MINOR |
| Something existing is removed or changed so that an existing client stops working | MAJOR |

Project-specific version rules (for example what happens before the first public launch) are in
`docs/operations/environments-and-releases.md`.

## What a release is

1. `release-prepare` writes `qa/releases/<version>/release.yaml`: the features and stages newly
   shipping, and the gates.
2. `release-verify` runs the gates against one revision and writes `RESULTS.md`.
3. `release-cut` marks the scope released.
4. Tagging and deployment are separate steps that happen only when the owner asks.

Regression covers the whole product delivered so far, not only the new scope: a release is the
moment to prove that earlier features still work.

## Milestones

When `docs/ROADMAP.md` groups features into milestones, a later milestone starts only once every
feature of the earlier one is `released`. A milestone may ship as more than one version.

## Reporting a release

Report the scope, each gate's result, the revision, and everything listed as deferred. A gate that
was skipped is reported as not certified, never as passed.
