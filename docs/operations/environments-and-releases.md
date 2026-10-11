# Environments and releases

The environments this product runs in, what a test may and may not touch, and the rules this
product imposes on a release. The release procedure itself (prepare, verify, cut) is in the
`salon-sdlc` skill.

## Environments

| Environment | State | Who may touch it |
| --- | --- | --- |
| Local | Created by stage F01-S01. The default for every build, test, QA run and bug fix | Any role |
| Staging | Not created yet (F01-S05). Will hold synthetic salons only | QA, when the owner's current message names staging |
| Production | Not created yet. Hosting provider and region are open decision D-08 in the BRD | The lead, when the owner's current message names production |

- An unqualified "run it", "test it", "QA it" or "fix it" means local.
- Permission to touch staging or production is given per request. It never carries over from an
  earlier message, and "cut a release" is not permission to deploy.
- When an environment is created, the stage that creates it reports its addresses, its start and
  stop commands and its data rules, and the lead adds them to this file.

## What a test or a developer may use

- **Payments:** the PayHere sandbox only. Never a live merchant account, never a real card.
- **Messages:** SMS and WhatsApp providers are switched to a local capture or a sandbox. Nothing run
  in development or QA may send a real message or cost money.
- **Salons:** the fixed synthetic salons in `docs/qa/test-plan.md`. Never real salon or customer data.
- **App stores:** nothing is submitted to Apple or Google by a build or a test run.
- **Load:** a load or stress test runs against the local machine only, unless the owner's current
  message names another target.

## Versions

The platform (API and web, deployed together) carries one version. Before the first public launch
it stays below `v1.0.0`, and every feature release is a MINOR bump. The customer app carries its
own store version and build number; a release manifest records which app version it was verified
with.

## Rules this product imposes on a release

- **Old app versions stay installed for months.** Database migrations and API changes are backward
  compatible; a breaking change needs a minimum-version bump (APP-11) and a MAJOR version.
- **Backend and web first, apps after.** A JavaScript-only app change can go over the air; a native
  change is a store release.
- **Each salon's dedicated app is its own listing** in the salon's own developer accounts. A release
  never publishes a salon's app from the operator's accounts (BRD section 7.4).
- **A rollback plan exists before a deployment starts:** the previous image or build, and a
  statement of whether the migrations can stay in place.
- **Milestones:** the web application is released before any work starts on the customer app. See
  `docs/ROADMAP.md`.
