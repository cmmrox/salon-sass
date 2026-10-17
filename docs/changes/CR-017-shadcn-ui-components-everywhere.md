---
id: CR-017
title: shadcn/ui components everywhere, including navigation
status: approved
requested_by: owner
requested_on: 2026-10-18
decided_on: 2026-10-18
requirements: []
features: [F01, F02]
stages: [F01-S03, F02-S04]
integration_branch: charithm/feature/F02-tailwind-shadcn
---

# CR-017 shadcn/ui components everywhere, including navigation

## Request

The owner, on 2026-10-18, after finding that the staff role dropdown is a native `<select>`
(shadcn/ui's Native Select) rather than the shadcn/ui Select: "i told you to use shardcn ui
component in every place for the existing development and also for the future implementation ...
why still have miss some components. please fix it review every places and conver to the shardcn
ui components as much as possible", linking `https://ui.shadcn.com/docs/components/base/select`.
Then: "i mean evey plases including menus and everyting convert to shardcn ui components" and
"please fix those and masure review it and everiting has converted to shardcn ui components".

## Why

CR-016 built every screen from shadcn/ui and Tailwind but let three things through: a native
control dressed as a field (Native Select), hand-composed versions of components shadcn/ui now
ships (a labelled field with its error, a password field with a toggle drawn over it, list rows,
empty states, a pager, status words), and the workspace navigation, which decision 046 and the
Styling rule 8 kept as the product's own component. CR-016 also asked for pixel-identical
screens, which pushed the developer towards the native control. The owner wants the shadcn/ui
component used wherever one exists for the job, with menus included, ahead of pixel identity with
the earlier build.

## What changes

The review of every screen on 2026-10-18 found these, each replaced by the shadcn/ui component
named (Radix primitives, style `new-york`, as installed by CR-016; the linked page's Base UI
variant has the same component API, and the product keeps one primitive library):

| Where | Now | Becomes |
| --- | --- | --- |
| Staff role on each member and in the create form | `NativeSelect` (native `<select>`) | **Select** (`SelectTrigger`, `SelectContent`, `SelectItem`); `ui/native-select.tsx` is deleted |
| Every labelled input with its refused-input message (`Field` in `account/Controls.tsx`) | Label wrapping Input plus a span | **Field** (`Field`, `FieldLabel`, `FieldDescription`, `FieldError`, `FieldGroup`) |
| Sign-in and account password fields with the visibility toggle | Button absolutely positioned over an Input | **Input Group** (`InputGroupInput`, `InputGroupAddon`, `InputGroupButton`) |
| Authenticator code at sign-in and at account enrolment | Plain six-character input | **Input OTP** (six slots) |
| Professional grant check box | Checkbox inside a Label | **Field** with `orientation="horizontal"`, Checkbox and `FieldLabel` |
| Device, staff member and audit entry rows | Card with grid classes | **Item** (`ItemGroup`, `Item`, `ItemContent`, `ItemTitle`, `ItemDescription`, `ItemActions`) |
| Active / Removed, Current device, role name | Plain text | **Badge** |
| Empty device, staff and audit lists | Paragraph | **Empty** |
| Previous / page / Next | Two buttons and a paragraph | **Pagination** |
| Denied page and the retry wait | Paragraph with a role | **Alert** |
| Busy sign-in and list loading | Text and skeletons | **Spinner** in the busy button, Skeleton kept for pending lists |
| Between a list and the create form | Heading margin | **Separator** |
| Status page "API is reachable" summary | Coloured paragraph | **Alert** with a positive variant |
| Workspace navigation: rail from 900px, bottom tabs below | Product `Navigation` component | **Sidebar** (`SidebarProvider`, `Sidebar`, `SidebarContent`, `SidebarMenu`, `SidebarMenuItem`, `SidebarMenuButton` with `isActive`, `SidebarTrigger`, `SidebarInset`). From 900px it is the fixed rail at the current widths (200px, 236px from 1200px); below 900px it opens as the Sidebar's own sheet from a `SidebarTrigger` menu button in the sticky header, replacing the bottom tab bar |

**Tailwind CSS everywhere** (the owner's follow-up "and telwind css"). The application has no
`style` prop or stray style sheet. The one place still styled by hand-written CSS is the proxy's
plain pages (`src/lib/plain-page/plain-page.css`, Styling rule 9), which are HTML strings answered
with no React, and which received a hand-written copy of the Card and Alert look because a raw
style sheet import is read before Tailwind runs. They are rebuilt with Tailwind utility classes in
their markup, with the same classes as the shadcn/ui Card and Alert they mirror. Their CSS is
produced by Tailwind from `globals.css`'s theme and the classes in `plain-page.ts`, by a script in
`apps/web` that writes a generated file. The generated file is committed and regenerated, never
edited, and a test fails when it is stale. `plain-page.css` is deleted. After this, the only CSS
written by hand is the theme (`theme.css`: variables and `@font-face`) and the Tailwind entry
(`globals.css`).

Unchanged, because there is no shadcn/ui component for the job: page headings, paragraphs, lists
of plain text and layout containers; the prototype's navigation glyphs, drawn inside
`SidebarMenuButton`. The confirmation overlay stays the shadcn/ui Sheet (bottom sheet on phones,
right drawer from 900px, CR-007). The plain pages cannot run React components, so they use the
components' classes as described above.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None added, changed or retired. NFR-21 still governs fidelity to the prototype's tokens, typography, spacing and composition, with the visible changes listed above accepted by the owner. |
| Design documents | `docs/engineering/web.md` "Styling" rule 1 becomes strict, with a component map; rule 8's navigation exception is removed; `docs/design/ui-ux-standards.md` records the Sidebar sheet below 900px in place of bottom tabs. Decision 048. |
| Features and stages | F01 and F02 are `complete` and not merged (their repair PR has not been opened). **F01-S03** (complete) reopens for the navigation, shell and status page; **F02-S04** (complete) reopens for the sign-in and account screens. Both to `in-progress`, `qa_status: not-run`, `reopened_by: CR-017`; both features to `in-progress`, `qa_status: not-run`. Same integration branch and one repair PR, as CR-016. |
| Test cases and automation | Cases that drive the role `<select>` with `selectOption`, the bottom tabs, the code input or that compare pixels with the pre-CR-016 build change. QA updates them at the feature pass. Test ids are kept where the element still exists; the developer lists every new, moved or retired test id in `handoff.notes` (for example the phone menu trigger). `components/account/composition.test.tsx` widens to every product component and forbids `NativeSelect`. |
| Data, API and released apps | None. One new npm dependency, `input-otp`, which shadcn/ui's Input OTP requires. |
| Size and delay | Web only: about 13 components added, `Navigation`, `WorkspaceShell`, `Controls`, `AccountScreen`, `SignIn` and `Status` rebuilt, and the plain pages moved to Tailwind-generated CSS. F03 keeps waiting for the repair merge. |

## Recommendation

Approve and implement now, on the open CR-016 repair branch, so that one PR carries the complete
shadcn/ui adoption.

## Decision

Approved by the owner's instructions of 2026-10-18 quoted above, which state the required outcome
directly.
