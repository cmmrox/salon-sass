---
id: CR-013
title: One salon theme across sign-in and workspace, and direct entry without a loading frame
status: approved
requested_by: owner
requested_on: 2026-10-17
decided_on: 2026-10-17
requirements: [NFR-21]
features: [F02]
stages: [F02-S04]
integration_branch: charithm/feature/F02-unified-theme
---

# CR-013 One salon theme across sign-in and workspace, and direct entry without a loading frame

## Request

The owner, on 2026-10-17, with two screenshots: "there is a loading frame which is not nice can you
fix it. it is show when i load the site also http://alpha.localhost:3100/ . also the login page
color and inside color also not in single theme. therefore fix those issue always go with the theme
and color which i provide in the design html but keep allow to change theme colors for salon side if
they want to change it. therefore also review the up to now implemented features and do the relevant
qa testing".

The first screenshot is a browser performance frame captured about 420 ms into loading
`http://alpha.localhost:3100/`: a placeholder "Salon" header and a white "Loading your account…"
card with three skeleton bars, shown for about 250 ms before the real screen. The second is the
current staff sign-in at the same address.

## Why

- **Loading frame.** `/` redirects to `/workspace`, then `/workspace/account`, which renders the
  workspace frame with a generic "Salon" name and the account skeleton before the browser learns
  there is no session and goes on to `/workspace/sign-in`. Every visitor without a session, and
  every visit by a signed-in one, sees an intermediate frame that is not the screen they asked for.
- **Two themes.** The supplied HTML prototype ends its style sheet with a "default brand" rule
  (`--ac:#D6086A`, `--acs:#D6086A1F`, `--onac:#fff`) that drives primary buttons, the selected
  navigation item, selected chips and slots, focus rings, progress bars and identity accents, and
  its brand settings let a salon change that one colour (`setCol`). The web app computes the
  salon's colour (`--color-brand`) but uses it nowhere: the workspace draws its accents in black
  (`--color-primary: #141413`, the prototype's intermediate "Mastercard" layer that its own final
  rule overrides), and CR-012's sign-in draws them in a separate taupe palette (`#8A705E`). So the
  sign-in, the workspace and the prototype show three different accent colours, and a salon's
  colour changes none of them.

## Decision on the theme

- The prototype's final effective tokens are the platform theme for the salon web application:
  warm canvas `#F3F0EB`, secondary surface `#EAE5DC`, lines `#DDD6CA`, white cards, ink `#141413`
  and muted `#6B7280`, Poppins, pills and rounded cards and fields, with the status colours as they
  are.
- **The accent is the salon's brand colour**, with its computed readable foreground. It is used
  wherever the prototype uses `--ac`, `--acs` and `--onac`, on the sign-in and in the workspace
  alike. When a salon has no valid colour, the accent is the prototype's default, `#D6086A`.
  A salon changes its colour through the API's brand data today and through the F04 brand
  settings later; no screen hard-codes a brand colour.
- CR-012's sign-in composition stays (interior photograph, centred salon initial and name,
  translucent card, headings, labels, eye toggle, recovery guidance, secure-team footer), but its
  separate taupe/grey palette is retired: its ink, muted text, borders, fields, focus, initial
  ring, link and action come from the same tokens as the workspace, with the brand accent.
- "Workspace stays neutral" (`docs/design/ui-ux-standards.md`, `docs/engineering/web.md`) is
  replaced for the salon web application. The operator console (F03, not built) keeps the neutral
  platform theme, because it belongs to no salon.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | NFR-21 changed by CR-013: one theme from the prototype's final tokens across sign-in and workspace, salon brand colour as the accent with `#D6086A` as default, and direct entry with no intermediate generic frame. |
| Features and stages | F02 (complete, merged) and F02-S04 (complete) reopen: F02-S04 `in-progress`, `qa_status: not-run`, `reopened_by: CR-013`; F02 `in-progress`, `qa_status: not-run`. F03 stays backlog until this repair merges. F01 is complete and merged; its shared shell and tokens are touched, so the owner-requested regression run covers it. |
| Test cases and automation | CR-012 sign-in visual expectations of a taupe action/accent become wrong; workspace expectations of a black selected navigation item become wrong. New expectations: brand accent on sign-in and workspace for a dark and a light salon colour, default accent when none, and no intermediate loading frame on entry. QA updates them at re-verification. |
| Data, API and released apps | No schema, API or contract change: `brandColour` already arrives from `/api/v1/salon`. No released app. |
| Size and delay | Web only: tokens, the sign-in and shell styles, and the entry route's server-side session decision. The next feature waits for this repair, its independent QA and the owner's merge. |

## Owner-requested QA

The same message asks for a review of everything implemented so far and the relevant QA. That is
an explicit request: QA independently re-verifies F02's changed scope (entry, sign-in, workspace
theme, branding with different salon colours) through registered F02 automation, writes a product
review of the salon web screens delivered so far (F01 and F02), and runs the regression suite over
the delivered features. Unaffected backend evidence is retained under CR-009. Local only; no
staging, deployment, real message or payment.

## Recommendation

Approve and implement now, before F03.

## Decision

Approved by the owner's instruction on 2026-10-17, which states the required outcome directly.
