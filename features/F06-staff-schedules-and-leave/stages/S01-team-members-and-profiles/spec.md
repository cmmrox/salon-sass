# F06-S01 Team members and profiles

## Objective

The salon's staff get a **Team** page. The Owner and Manager add, edit and remove the people who
perform services: a profile with a photo, the services each performs, whether customers can see them
for online booking, and an optional link to a sign-in account. A person can be booked without a
sign-in, and counts as one staff seat either way. Anyone can read the profiles shown for online
booking through a public API, which the customer app (F15) uses, and F07 reads who can perform what
through a provided interface.

## Read first

- `docs/brd/requirements.yaml`: STF-01, PLT-04.
- `features/F06-staff-schedules-and-leave/README.md`: what is built and what is not, the link, the
  questions asked of `booking`.
- `docs/decisions/DECISION_LOG.md`: decisions 073, 081, 082, 086, 087, 093, 094, 099 and 100.
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Tests you must
  write".
- `docs/architecture/identity-and-access.md`: "Role permissions" (`team.read`, `team.manage`, support
  sessions), "Implemented web boundary".
- `docs/architecture/operator-console.md`: "Entitlements" (the seat rule).
- `docs/engineering/backend.md`: modules, "Provided interfaces" (in-process contracts), "Ownership of
  tables and paths", "Persistence and migrations", "Security" (uploads), "Tests".
- `features/F05-service-catalogue/stages/S01-categories-and-services/spec.md`: AC-10 and AC-20, how
  service photos are checked, stored and served, which team photos follow.
- `docs/engineering/web.md`: BFF rules, "Styling" (the component map), "Tests".
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon web, **Staff**, in the
  F06 block at the end of the file: `aStaff`, `tmTabs`, `acctTab`, `stOpen`, `stErr`, `stCatRow`,
  `stPaint`, `stSave`, `stDel`, and `stDelGo` earlier in the file.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| STF-01 | Staff profile: name, photo, title, short biography, the services they perform, and whether they are shown for online booking. Team members can be added, edited and removed; removal is refused while the person has upcoming bookings. A professional can be bookable without a sign-in account. | Full on the staff side. The guard asks `booking` through an interface that answers "none" until F07. The customer screen is F15's (decision 082) |
| PLT-04 | Plans define features and limits (staff seats, branches, message allowance, custom domain, dedicated app). Entitlements are enforced by the backend and reflected in every client. | In part: staff seats count team members (decision 099) |

## Scope

- **API (`staff`):** team members, their services and photos under `/api/v1/team`; the public team
  list; the provided interface `TeamMembers`; the declared interface `UpcomingMemberBookings` with an
  implementation that answers "none".
- **API (`identity`):** the permissions `team.read` and `team.manage`; the seat check that also counts
  members with no linked account; the in-process contract `staff` implements; a provided way to list the
  salon's active accounts and to check one; clearing a link when an account is removed.
- **API (`catalogue`):** a provided interface that lists the salon's categories and services for `staff`.
- **API (`platform`):** seat usage and the plan-move check include members with no linked account.
- **Web:** the **Staff** navigation entry now opens the Team page, with **Team** and **Sign-in
  accounts** tabs, the person cards and the profile sheet. The Billing page's seat usage follows.

## Out of scope

- Weekly hours, date changes, the Schedule button and the Hours row on a card (F06-S02).
- Leave, blocked time and `team.block` (F06-S03).
- Booking a person, availability, and showing a person's bookings (F07).
- The customer's team screens (F15).
- Price levels (F17, decision 081), consultations (F17), ratings (STF-07), commission (STF-08),
  branches a person works at (F18), an owner badge, and closing the salon from this page. The sheet
  shows no control for any of them.
- Creating, editing or removing sign-in accounts, which stays F02's screen, unchanged apart from AC-11.

## Acceptance criteria

- AC-1: An Owner or Manager adds a team member with these fields and checks:
  - name, 2 to 40 characters, trimmed; two people may share a name;
  - title, up to 40 characters, optional;
  - highlights, up to 60 characters, optional;
  - short bio, up to 140 characters, optional, with a live count "65/140";
  - shown for online booking, on by default;
  - services, as AC-3, which may be empty;
  - sign-in, as AC-5, none by default.

  A salon has at most 200 team members. A broken rule is a 400 field error on that field, nothing is
  saved, and the sheet shows the message and disables **Add to team** while it lasts. On success the
  screen says "Added to the team." A salon starts with no team members. (STF-01)
- AC-2: An Owner or Manager edits a member through the same sheet ("Profile saved."). A save that
  changes nothing is 409 `invalid_state`. A stale `version` is 409 `conflict` and changes nothing, and of
  two saves of the same version sent at once exactly one succeeds. (STF-01)
- AC-3: **Services they perform** lists the salon's categories that have services, in the salon's
  order. Ticking a category means every service in it, and any added to it later ("All 3 services, and
  any added later"). **Choose** narrows a ticked category to single services ("2 of 3 services"), and
  services added to that category later are then not included. Unticked, a category reads "Not performed".
  Sent or stored, a person has whole categories and single services; an id of neither the salon's
  categories nor its services is a 400 on `services`, and so is a single service inside a category also
  sent whole. A person's services are worked out when read: a service moved to another category follows
  the category rules at once, and a deleted service disappears from the person. (STF-01, decision 099)
- AC-4: `TeamMembers`, for the caller's salon only, returns a member by id, including a removed one
  for its name; the members who perform a given service; the services a member performs; and the member
  linked to a given account. Each carries id, name, whether they are removed, and whether they are shown
  for online booking. Another salon's ids are "not found" or absent. Javadoc names it as the contract F07
  and F10 use. (STF-01)
- AC-5: **Sign-in** offers "No sign-in. They can still take bookings" and each active account of the
  salon not linked to another member. A member can be saved, listed, returned by `TeamMembers` and by
  the public API with no account. An account of another salon, a removed one, or one already linked is a
  400 on `signInAccountId`, and two saves linking one account at once leave it linked once. Removing a
  member leaves their account active and free to link. Removing an account leaves the member on the team
  with no sign-in, cleared in the transaction that removes the account. (STF-01, decision 100)
- AC-6: Seats. Seats used are active accounts plus active members with no linked account, as the
  Billing page and the operator's plan move both report and check:
  - adding a member with no sign-in, or unlinking a member whose account stays, needs a free seat;
  - adding a member linked to a free account needs none;
  - creating a sign-in account needs a free seat;
  - linking a member with no sign-in to a free account frees a seat;
  - no seat is needed to edit a member without changing the link.

  With no free seat the API answers 409 `entitlement_exceeded` naming `staffSeats`, nothing changes, and
  the sheet shows the existing message. Of two requests for the last seat sent at once, in any mix of
  adding a member and creating an account, exactly one succeeds. A plan move to a plan below the seats
  used is refused as today, with members counted. A salon without a plan is not limited. (PLT-04,
  decision 099)
- AC-7: **Remove from team** asks first ("Remove <name>?" / "They will no longer be available for
  booking. Past bookings keep their name. Their sign-in account, if any, stays until you remove it.") and
  then says "Removed from the team." When `UpcomingMemberBookings` reports an upcoming booking for the
  person, the API answers 409 `in_use`, nothing changes, and the sheet says "<name> has N upcoming
  bookings. Move or cancel them first." A removed member leaves the list, the public API and the seat
  count, and `TeamMembers` still returns their name. An integration test with an implementation that
  reports a booking proves the refusal. (STF-01)
- AC-8: A save that stops a person performing a service with an upcoming booking for them is 409
  `in_use`, nothing changes, and the sheet says "This person has upcoming bookings for <services>. Move them
  first." Proved the same way as AC-7. (STF-01)
- AC-9: A member may have one photo, under the rules of F05-S01's AC-10: PNG, JPEG or WebP of up to 5 MB
  and at least 320 × 320 pixels, checked by decoding, cropped to its centre square, re-encoded without
  metadata, and stored as a JPEG of at most 800 × 800, served by an opaque id on the salon's own hostname
  through the BFF. As in F05-S01's AC-20, permission, the target and the declared size are checked before
  the bytes are read. **Remove** removes it, and a replaced or removed photo's URL answers 404. A file that
  breaks a rule is refused with the reason, and the member keeps their photo. With no photo the card shows
  the person's initial on a colour chosen from the prototype's six by their id. (STF-01)
- AC-10: `GET /api/v1/team/public` needs no sign-in. It returns the members shown for online booking who
  perform at least one service bookable online: id, name, title, highlights, bio, photo URL or `null`,
  and those services' ids, in name order. It never returns a hidden or removed member, a sign-in account
  or a version. Another salon's team is never returned, and a suspended or deleted salon answers as the
  other public salon endpoints do. (STF-01)
- AC-11: The **Staff** navigation entry shows for every role with `team.read` and opens
  `/workspace/team`, the **Team** tab. A role that also has `staff.list` sees the **Sign-in accounts**
  tab, which is the existing `/workspace/staff` screen with its behaviour unchanged, and each account there
  names its linked team member or says "No team profile". Without `staff.list` no tab bar shows, and
  `/workspace/staff` answers as it does today. (STF-01, decision 100)
- AC-12: The **Team** tab shows a card per member in name order: photo, name, title, bio, "Services" with
  the names of the categories they perform or "None yet", and an "Online booking" badge of "Shown" or
  "Hidden". With `staff.list` it also shows "Sign-in" with the account's label or "None". The header says "N
  people · M shown for online booking". With no members it shows the Empty state "Add your first team
  member" and, with `team.manage`, **+ Add team member**. (STF-01)
- AC-13: Roles:
  - `team.read` (Owner, Manager, Receptionist, Professional, a support session) reads the Team tab;
  - only `team.manage` (Owner, Manager) sees and uses Add, Edit profile, Remove and photo changes;
  - an Accountant gets no navigation entry and 403 `permission_denied` from every team endpoint;
  - other roles get 403 `permission_denied` from every change endpoint;
  - only a caller with `staff.list` receives `signInAccountId` and its label, and the list of accounts;
  - a support session is refused every change with 403 `support_read_only`.

  Suspended and read-only salons answer as the catalogue endpoints do. (STF-01)
- AC-14: Tenant isolation: another salon's members and photos are never readable or changeable and answer
  "not found". A member cannot perform another salon's category or service or link to another salon's
  account. With no tenant context the new tables return no rows. (STF-01)
- AC-15: Every change to a member, including services, online setting, link, photo and removal, is
  written to the salon's audit trail with actor, time and before and after values. (STF-01)
- AC-16: The Team tab and the sheet provide, at every width `docs/design/ui-ux-standards.md` names:
  - loading, empty, field-error, conflict, refused and unavailable states;
  - a leave-with-unsaved-edits guard on the sheet;
  - keyboard-only use, with accessible names for the photo control and each category's **Choose**;
  - no WCAG 2.2 AA violation in the accessibility check.
  (STF-01)

## Design notes

- **Module and dependencies.** `staff` gains `catalogue` and `shared :: entitlements` in its allowed
  dependencies, and `identity :: inprocess` for the seat contract. `catalogue` does not depend on `staff`.
  Update `module-ownership.yaml` with the tables, `/api/v1/team` and `salon.staff.images`, and its
  description.
- **Branch scope.** Every new table carries `tenant_id` and `branch_id` (the default branch). The team is
  salon-wide in R1.
- **A member's services.** Two child tables: whole categories, and single services. Hold ids only (no
  foreign key crosses modules). `catalogue` provides a new interface listing the salon's categories and
  services, each with its category and whether it is bookable online. A read intersects with it, so a
  stale id is simply not shown, and a save replaces the rows.
- **The link.** A nullable account id on the member, with a partial unique index on salon and account for
  members not removed, so the database refuses a double link under simultaneous saves.
- **Seats (decision 100).** `identity` already checks seats under its salon lock. It declares in
  `identity.inprocess` the contract `staff` implements (how many active members have no linked account;
  clear the link of a removed account) and offers `staff` the check, joining `staff`'s transaction and
  taking the same lock first. Take the lock before any `staff` row, in that order everywhere, so no
  deadlock arises with an account being created. Identity must work with no implementation registered,
  so its own tests stand alone. Its Javadoc says why this is in-process. Add
  `platform.inprocess` hook and a `SECURITY DEFINER` function, `staff_active_unlinked_member_count(target_tenant)`,
  executable by the platform role only, for usage and the plan move, and add it to the database
  isolation test's list. The count also feeds `usage.staffSeats`.
- **The guard (decision 100).** `UpcomingMemberBookings` is declared in `staff`'s root package with an
  implementation in `staff` that answers "none", replaced when `booking` provides its own in F07. It asks
  whether a member has upcoming bookings and which of a set of services they are booked for. Document the
  replacement in its Javadoc, and that the question is asked before the transaction, as in decision 094,
  so F07 must close that window.
- **Removing.** A removed member is a tombstone: it leaves every list and every count, and clears its link
  and its service rows' effect, while its id and name stay for past bookings.
- **Photos.** Use the shared image checks. `staff` keeps its own photo table and limits under
  `salon.staff.images.*`, serving as `catalogue` does.

## API and data changes

Routes are `staff`'s, on the salon's workspace host. Responses carry `version` for optimistic
concurrency.

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/team/members` | `team.read` | `{items: [member]}`, whole (capped at 200) |
| `GET /api/v1/team/members/{id}` | `team.read` | The member |
| `POST /api/v1/team/members` | `team.manage` | `{name, title, highlights, bio, shownOnline, services: {categoryIds, serviceIds}, signInAccountId}` -> 201 |
| `PUT /api/v1/team/members/{id}` | `team.manage` | The same fields with `version` -> 200 |
| `DELETE /api/v1/team/members/{id}?version=` | `team.manage` | 204; 409 `in_use` |
| `PUT /api/v1/team/members/{id}/photo` | `team.manage` | Multipart `file` -> 200 the member; `DELETE` removes it |
| `GET /api/v1/team/sign-in-accounts` | `team.manage` and `staff.list` | `{items: [{id, label, role, memberId}]}`, the salon's active accounts |
| `GET /api/v1/team/photos/{photoId}` | none (public) | The image |
| `GET /api/v1/team/public` | none (public) | `{items: [{id, name, title, highlights, bio, photoUrl, serviceIds}]}` |

A member is `{id, name, title, highlights, bio, shownOnline, photoId, photoUrl, services: {categoryIds,
serviceIds}, signInAccountId, signInLabel, version}`, the last two omitted without `staff.list`. Field
names may follow the API conventions where they differ; the handoff records the final shapes.

Append migrations with the full "Adding a table" checklist (members, whole categories, single services,
photos) and the count function. No existing salon gains a member. Add the permissions to `StaffRole` and
`team.read` to the support read set. Validate every new response with the contract validator.

## UI and UX

Follow the prototype (`aStaff`, `tmTabs`, `stPaint`, `stCatRow`) and the component map:
- Tabs for Team and Sign-in accounts, shown only with `staff.list`;
- Card for each person, Avatar with the initial fallback, Badge for "Shown" and "Hidden";
- Sheet for the profile form, Alert Dialog to confirm removal;
- Field with Input or Textarea, Field with Checkbox or Switch for categories and "Show for online booking",
  Toggle with the `chip` variant for single services, Select for Sign-in;
- the file control of the component map for the photo, with its preview;
- Empty, Skeleton, Spinner, Alert and Sonner.

| Screen | Route | Behaviour |
| --- | --- | --- |
| Team | `/workspace/team` | The cards of AC-12 and **+ Add team member**; each card has **Edit profile** with `team.manage` |
| Sign-in accounts | `/workspace/staff` | F02's screen under this page's tabs, as AC-11 |
| Profile sheet | over Team | The fields of AC-1 in the prototype's order: photo, Name, Title, Highlights, Short bio, Services they perform, Online booking, Sign-in. **Add to team** or **Save changes**, and **Remove from team** for an existing person. Without `staff.list` a caller cannot reach it, since only Owner and Manager have `team.manage` |

The prototype's closing line about opening hours and the card's Hours row and **Schedule** button appear in
F06-S02. A photo on a new member is added after the member is created, as for a service ("Add a photo after
you add this person."). Messages go through next-intl, English only in R1. Every interactive element has a
`data-testid`. The navigation entry's test id becomes `workspace-team`; QA updates the delivered F01 and F02
checks that name `workspace-staff`.

## Risks and test focus

- `concurrency`: the last seat under simultaneous member creation and account creation, in both orders, is
  the highest risk of this stage; stale versions; two saves linking one account; no deadlock between
  `identity`'s lock and `staff`'s rows.
- `data-integrity`: seat counts across link, unlink, account removal and member removal; a removed member's
  name stays readable; services worked out correctly after a service moves or is deleted, and after a whole
  category gains a service; removing an account clears the link in one transaction.
- `tenant-isolation` and `permissions`: every table and endpoint; the public list and photo; each role and a
  support session; another salon's category, service and account.
- `security`: uploads as F05-S01; the public list exposes no account, no version and no hidden member; the
  account label and id only with `staff.list`.
- `api` (validation and contract): every field boundary (name 1, 2, 40, 41; title 40, 41; highlights 60, 61; bio
  140, 141; the 201st member); every response, including `null` photo, omitted sign-in fields and the 409 codes.
- `regression`: F02's staff screen and role navigation, and F03's plan move and Billing seat usage, as both change.
- `functional`, `responsive`, `accessibility`: the tab, the sheet, read-only roles, the Empty state.

## Verification commands

```bash
cd apps/api && ./mvnw -q verify
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web test:a11y
pnpm --filter @salon/web build
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product with two synthetic salons, each
  role and a support session, and recorded it in the handoff.
- The verification commands are green.
- `TeamMembers`, `UpcomingMemberBookings` and the seat contract are documented in their Javadoc as the contracts
  F07, F10 and `identity` use.

## Corrections at close

Recorded by the lead when the stage closed (decision 101). No requirement changed. These were given to the
developer during the build and take precedence over the criteria above:

- **The 201st member** is 409 `limit_reached`, not a 400 field error, as F05's limits are. The sheet says "A salon can
  have up to 200 team members. Remove someone first."
- **`in_use` details** are in the `errors` items: `{field:"upcomingBookings", detail:"<N>"}` on removal, and one
  `{field:"services", detail:"<service name>"}` per service on a save.
- **A removed person's photo** is deleted, and its URL answers 404 from the moment they are removed.
- **Singulars.** A category with one service reads "1 service, and any added later"; the header reads "1 person · 1 shown
  for online booking".
- **Name order** ignores case, on the Team tab and in the public list.
- **Messages** state the limit the rule applies: "Title must be 40 characters or fewer.", "Highlights must be 60
  characters or fewer." and "Bio must be 140 characters or fewer." The prototype's "under" wording is not used.
- **`maxLength` is left off** Name, Title, Highlights and Bio, so that the sheet can show those messages and the count can
  read 141/140. F05's sheets differ.
- **Consultations in the picker.** AC-3 stands: "Consultations" is an ordinary category of the salon, and appears once it
  has services. Without it nobody could perform those services. What F17 owns is consultation pricing and the
  prototype's "Takes consultations" chips, which are not built.
- **Plan moves.** A seat request that waits behind a plan move is held to the plan the move committed. This corrects F03's
  `salon_entitlements()` in V29, a change to delivered F03 behaviour.
- **As built.** A photo change raises the member's version and takes no identity lock, as it takes no seat. A save with
  `services` absent means none, and with `signInAccountId` absent means no sign-in. A support session receives the sign-in
  fields, since it holds `staff.list`. `DELETE /local/identity` clears links. The navigation label is "Staff" and the
  page title "Team", on `/workspace/staff` too, with F02's "Staff access" kept as that screen's section heading.
- **Not proved on the running product until F07:** AC-4 (a Java interface), AC-8 and AC-7's refusal, which are proved by
  integration tests with an implementation that reports a booking, and by screen tests with the answer mocked.

## CR-026 (follow-up after the merge of PR #23)

This replaces AC-1's "shows the message ... while it lasts" for a blank sheet, and is built and verified in one follow-up PR after F06's (decision 098). Read
`docs/changes/CR-026-team-page-refinements.md` and decision 107.

- AC-17: **+ Add team member** opens with no field error: Name is not outlined and no message shows. Once Name has been
  typed in, or left, a name that breaks the rule shows "Name must be 2 to 40 characters." as today. **Add to team** stays
  disabled while the name is not valid, before and after the message shows. Editing an existing member is unchanged.
  (STF-01, CR-026)

## CR-030 (follow-up after the merge of PR #23)

Built and verified in the follow-up PR #31 (decision 098). Read
`docs/changes/CR-030-public-photos-in-the-visitors-browser.md`.

- AC-18: A team member's photo answered 200 on its salon's hostname carries `Cache-Control: private, max-age=3600,
  immutable`, and never `public` or `s-maxage`: a person's photo that is removed stops being shown from a browser's
  cache within the hour. Any answer that is not 200, and any answer on a suspended or unknown salon's hostname, is
  `no-store`. The same photo path asked on another salon's hostname is 404 and `no-store`. (STF-01, CR-030)
