---
id: CR-016
title: Tailwind CSS and shadcn/ui for every web screen
status: approved
requested_by: owner
requested_on: 2026-10-17
decided_on: 2026-10-17
requirements: []
features: [F01, F02]
stages: [F01-S03, F02-S04]
integration_branch: charithm/feature/F02-tailwind-shadcn
---

# CR-016 Tailwind CSS and shadcn/ui for every web screen

## Request

The owner, on 2026-10-17: "makesure this application use tailwind css shadcn ui components always.
thefore you have to stick with this. can you check and tell me the nexjs project setup at that way
and you have use it upto now developments". After the lead reported that the web app uses CSS
custom properties with CSS Modules and no utility-class framework (decision 014): "therefore
redesign all the things to use that way and also update the relavent docs because in future
development also you have to stick with Tailwind + shadcn/ui for the whole nextjs developments in
this application".

## Why

`apps/web` is styled with hand-written CSS Modules over the tokens in `src/styles/tokens.css`
(decision 014), and every control (buttons, fields, cards, notices, dialogs, tables, navigation)
is hand-built. The owner wants one standard for all Next.js work in this product: Tailwind CSS for
styling and shadcn/ui for components, now and in every later feature. A shared component library
removes hand-built look-alikes, and its accessible primitives (focus management, dialogs, menus)
are maintained upstream.

What this changes and what it does not:

- **Changes:** how the web app is styled and which components it is built from. CSS Modules and
  hand-written component CSS are retired; screens are rebuilt from shadcn/ui components and
  Tailwind utilities.
- **Does not change:** what any screen looks like or does. The HTML prototype's final tokens, the
  CR-012 sign-in composition and the CR-013 theme stay the visual authority (NFR-21). shadcn/ui's
  own default palette is not used: its theme variables carry the prototype's values. The salon's
  brand colour is still applied at run time through CSS variables, which is how shadcn/ui themes
  anyway, so the reason behind decision 014 still holds. Routing, sessions, tenant isolation,
  translations, test ids and API contracts are untouched.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None added, changed or retired. NFR-21 (design fidelity) governs the result unchanged. The styling rule is an engineering decision: decision 046 replaces decision 014's styling choice and amends 031 for the plain pages. |
| Features and stages | F01 (complete, merged by PR #7) and F02 (complete, merged by PR #11) reopen together on `charithm/feature/F02-tailwind-shadcn`, as CR-007 and CR-010 did. **F01-S03** (complete) reopens: Tailwind and shadcn/ui setup, theme, shared shell, navigation, status, notice and not-found screens, operator console layout, plain proxy pages. **F02-S04** (complete) reopens: staff sign-in, account, devices, MFA, staff and audit screens and confirmation dialogs. Both go to `in-progress`, `qa_status: not-run`, `reopened_by: CR-016`; both features to `in-progress`, `qa_status: not-run`. F01-S01's status page is restyled under F01-S03 (as in CR-007), with its behaviour unchanged. API-only stages are untouched. F03 stays backlog until this repair merges. |
| Test cases and automation | QA automation selects by `data-testid` and checks rendered styles, not class names, so no case is expected to become wrong. Test ids are kept; a renamed one is announced in `handoff.notes`. Developer tests that read `tokens.css` or CSS Modules (`styles/theme.test.ts`, `styles/positive-contrast.test.ts`, `lib/plain-page/plain-page.test.ts`) move to the new theme file. New: a developer test that enforces the styling rules. QA re-verifies F01 and F02 web automation at the feature pass, including the visual, theme, width-matrix, keyboard and dialog cases. |
| Data, API and released apps | No schema, API, contract or permission change. No released app. |
| Size and delay | Web only. About 28 components and 13 stylesheets are rebuilt, plus new `src/components/ui/` components. F03 waits for this repair, its independent QA and the owner's merge. |

## Delivery

1. F01-S03 first: install and configure Tailwind CSS 4 and shadcn/ui, move the tokens into the
   theme, add the `ui/` components the shell needs, rebuild the shell, navigation, status, notice,
   not-found and plain pages, and add the styling-rules test. Closed on the developer's gates.
2. F02-S04 on top of it: rebuild the sign-in and workspace account screens from shadcn/ui
   components. Closed on the developer's gates.
3. Independent QA of F01 and F02's affected web scope at one feature pass each, keeping
   unaffected API evidence under CR-009. Then one repair PR for the owner to merge.

Local only. No staging, deployment, tag, real message or payment.

## Recommendation

Approve and implement now, before F03, so that the operator console and every later screen start
on the new standard.

## Decision

Approved by the owner's instruction on 2026-10-17, which states the required outcome directly and
asks for the documents to be updated so later development keeps to it.
