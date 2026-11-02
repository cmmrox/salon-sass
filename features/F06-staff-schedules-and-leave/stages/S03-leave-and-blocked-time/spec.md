# F06-S03 Leave and blocked time

## Objective

The schedule sheet gains **Leave** and **Blocked time**. The Owner and Manager record leave as whole days, and they or a
Receptionist block part of a day with a label. Customers cannot be offered that time, and F07 reads it from the same
`TeamSchedule` answer. A booking already in that time is kept and flagged for the salon to deal with; nothing is
cancelled for them. This is the last stage of F06.

## Read first

- `docs/brd/requirements.yaml`: STF-03, STF-05.
- `features/F06-staff-schedules-and-leave/README.md`, and F06-S01's and F06-S02's `spec.md` with their "Corrections at close".
- `docs/decisions/DECISION_LOG.md`: decisions 087, 099, 100, 101, 102, 103 and 104.
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Tests you must write".
- `docs/architecture/identity-and-access.md`: "Role permissions" (`team.block`, what a support session reads).
- `docs/engineering/backend.md`: "Provided interfaces", "Persistence and migrations", "Time", "Tests".
- `docs/engineering/web.md`: BFF rules, "Styling" (the component map), "Tests".
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon web, **Staff**, in the F06 block at the end of
  the file: `schLv`, `lvHit`, `lvSave`, `schBl`, `blSave`, `conflictNote`, `nextOff`, `schTabs`, `dayRg`.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| STF-03 | Leave and time off. Existing bookings in the period are kept and flagged for action; none are cancelled automatically. | Full on the staff side. Which bookings are in a period is asked of `booking` through an interface that answers "none" until F07. F10 shows them on the calendar |
| STF-05 | Blocked time on a professional's calendar (training, errands) with a label. | Full for keeping it. F10 draws it on the calendar |

## Scope

- **API (`staff`):** leave and blocked time for a member, `TeamSchedule` taking them out, and `MemberBookingsAffected` also asking
  about a period of days and a part of a day. The permission `team.block`.
- **Web:** the **Leave** and **Blocked time** tabs on the schedule sheet and the card's leave badge.
- **Shared web:** the Sheet's phone handle, which measures 0px when its content overflows (decision 103).

## Out of scope

- Showing leave, blocked time and flagged bookings on a calendar, and acting on a flagged booking (F10).
- Offering times, and any check made when a booking is made (F07, reading `TeamSchedule`).
- A Professional asking for leave, approving leave, leave balances and accrual (decision 099).
- Repeating blocked time, and leave for part of a day: part of a day is blocked time.
- Any of it in the public team list.

## Acceptance criteria

- AC-1: On **Leave** an Owner or Manager adds leave with a first day and a last day, a type (Annual leave, Sick leave, Personal,
  Other) and an optional note of up to 60 characters. The first day is today or later, and the last day is on or after the first
  and at most one year ahead, by the salon's date. A broken rule is a 400 field error ("The last day can’t be before the first."),
  and nothing is saved. On success the screen says "Leave added." or, with bookings in the period, "Leave added. Existing bookings
  were kept and flagged." (STF-03)
- AC-2: Two leaves of one person may not overlap: the second is 409 `conflict`, the sheet says "This overlaps leave already
  planned.", and of two overlapping requests sent at once exactly one is kept. A person has at most 100 upcoming leaves, the next
  being 409 `limit_reached`, which holds under simultaneous requests. (STF-03)
- AC-3: The tab lists leave that has not ended, in date order: its days, as "Thu 12 Nov to Sat 14 Nov" or one day alone, its type
  and its note, and "N bookings to resolve" when there are any. It otherwise says "No leave planned." **Remove** asks first ("Remove
  this leave?" / "They become bookable on these days again."), including leave already under way. Removing leave that has ended or
  is gone answers 404. (STF-03)
- AC-4: On **Blocked time** an Owner, Manager or Receptionist blocks part of one date, today or later and at most one year ahead,
  from a start to a finish in 15-minute steps, with a label of 2 to 40 characters, trimmed. A broken rule is a 400 field error ("The
  end must be after the start.", "Add a label, like Training."), and nothing is saved. On success the screen says "Time blocked." or
  "Time blocked. The existing booking was kept and flagged." (with N bookings, "N existing bookings were kept and flagged.")
  (STF-05, decision 099)
- AC-5: Two blocks of one person may touch but not overlap: the second is 409 `conflict`, the sheet says "This overlaps time already
  blocked.", and of two overlapping requests sent at once exactly one is kept. A person has at most 200 upcoming blocks, the next
  being 409 `limit_reached`, which holds under simultaneous requests. A block may lie outside the person's hours or on their leave,
  and is stored as entered. (STF-05)
- AC-6: The tab lists blocks from today on, in date and time order: date, times and label, and it otherwise says "No blocked time."
  **Remove** takes one away ("Blocked time removed."), and removing one that has passed or is gone answers 404. (STF-05)
- AC-7: `TeamSchedule` now takes both out, with no change to its signature for F07:
  - a date within leave is not worked, with no windows, whichever way its hours or date change read;
  - a block is taken out of the windows, including at their edges and across a break;
  - a day whose blocks leave no time is not worked, with no windows, so that `working: true` always has a window (decision 103);
  - the answer says what decided the day: leave, blocked time, a date change or the week.

  Removing a leave or a block restores the answer on the next call. It still ignores opening hours, closures and holidays. (STF-03,
  STF-05)
- AC-8: Both forms show, before saving, the bookings the period would affect, from `MemberBookingsAffected`: date, time, customer and
  service. The sheet says "N existing bookings in this time. They are kept and flagged for you. Nothing is cancelled." and "1 existing
  booking in this time. It is kept and flagged for you. Nothing is cancelled." Saving is allowed. Nothing about a booking changes, and
  this stage stores no flag: a booking is flagged while it lies in leave or a block, which is worked out when read, so removing the
  leave clears it. An integration test with an implementation that reports a booking proves the list, the count in AC-3, and that the
  booking is untouched. (STF-03, STF-05, decision 104)
- AC-9: Each card shows a badge for the person's current or next leave: with `team.manage` its type and days ("Annual leave, Thu 12 Nov
  to Sat 14 Nov"), and otherwise "On leave, Thu 12 Nov to Sat 14 Nov". With none, no badge. (STF-03, decision 104)
- AC-10: Roles:
  - `team.read` (Owner, Manager, Receptionist, Professional, a support session) reads a person's leave days and blocks, read-only
    where it lacks the change permission;
  - only `team.manage` (Owner, Manager) adds or removes leave and receives a leave's type and note. Others see only its days;
  - `team.block` (Owner, Manager, Receptionist) adds and removes blocks. A Professional and a support session cannot;
  - other roles get 403 `permission_denied` from every change endpoint, and a support session 403 `support_read_only`;
  - an Accountant gets 403 `permission_denied` from every endpoint here;
  - neither leave nor blocks appear in the public team list.

  Suspended and read-only salons answer as the catalogue endpoints do. (STF-03, STF-05, decision 099)
- AC-11: Tenant isolation: another salon's member, leave and blocks are never readable or changeable and answer "not found", including
  through `TeamSchedule`. With no tenant context the new tables return no rows. (STF-03, STF-05)
- AC-12: Every leave and block added or removed is written to the salon's audit trail with actor, time and before and after values. A
  leave's note is never written there, which records `hasNote`, and neither is its type visible to a support session through the audit:
  the audit records that leave was added or removed, with its days. A block's label is written. (STF-03, STF-05, decision 104)
- AC-13: Both tabs provide, at every width `docs/design/ui-ux-standards.md` names: loading, empty, field-error, conflict, refused and
  unavailable states; keyboard-only use with accessible names; no sideways scrolling at 360 pixels; a leave-with-unsaved-edits guard on a
  started form; and no WCAG 2.2 AA violation in the accessibility check. (STF-03, STF-05)
- AC-14: On a phone, the Sheet's handle is visible at the prototype's size when its content overflows, on the schedule and profile sheets,
  and on one sheet of each delivered feature that has one. No other part of any sheet changes. (decision 103)

## Design notes

- **Module.** `staff` only, plus `identity`'s `StaffRole` for `team.block`, and the shared Sheet in the web. No new module dependency.
- **Branch scope and time.** As F06-S02: `branch_id` on every table; local dates and times; "today" from the `Clock` in the salon's zone.
- **Storage.** Leave is a row with a first and last date, type and note; a block a row with a date, start, finish and label. Enforce steps,
  order and the label with `CHECK`s. Adding either takes the member's row with a lock, as a date change does, which holds the overlap
  rule and the limit under simultaneous requests and stops one being added to someone being removed. Removal stays a tombstone: their
  rows stay, unreadable by any route, until salon erasure, which learns the new tables.
- **Leave's type is private (decision 104).** A sick day is personal. A caller without `team.manage` receives only the days.
- **Flags are worked out, not stored (decision 104).** `MemberBookingsAffected` gains the period questions. The count in AC-3 and the list in
  AC-8 come from it, so nothing can go stale when leave is removed or a booking moves. F10 reads the same answer.
- **Double booking.** Leave and blocks are not in `booking`'s exclusion constraint. F07 reads `TeamSchedule` when it offers a time, and a
  booking that races a new block is simply flagged.
- **The handle.** First confirm the cause. The developer of F06-S02 read it as `before:h-1` with no `shrink-0` in a flex column, which is not
  proved. Fix it in `components/ui/sheet.tsx`, and prove it with a test that fails without the fix.

## API and data changes

Routes are `staff`'s, on the salon's workspace host. Dates are `YYYY-MM-DD`, times `HH:mm`.

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `POST /api/v1/team/members/{id}/schedule/leave` | `team.manage` | `{firstDay, lastDay, type, note}` -> 201 `{leave, affected}` |
| `DELETE /api/v1/team/members/{id}/schedule/leave/{leaveId}` | `team.manage` | 204 |
| `GET /api/v1/team/members/{id}/schedule/leave/affected?firstDay=&lastDay=` | `team.manage` | `{affected}` |
| `POST /api/v1/team/members/{id}/schedule/blocked-time` | `team.block` | `{date, starts, finishes, label}` -> 201 `{block, affected}` |
| `DELETE /api/v1/team/members/{id}/schedule/blocked-time/{blockId}` | `team.block` | 204 |
| `GET /api/v1/team/members/{id}/schedule/blocked-time/affected?date=&starts=&finishes=` | `team.block` | `{affected}` |

`GET .../schedule` gains `leave` and `blockedTime`, from the salon's today on. A leave is `{id, firstDay, lastDay, type, note, bookingsToResolve}`,
`type` and `note` absent without `team.manage`. A block is `{id, date, starts, finishes, label, bookingsToResolve}`. `GET /api/v1/team/members` and
`/{id}` gain `nextLeave`, being `{firstDay, lastDay, type}` or `null`, `type` absent without `team.manage`. `type` is `annual`, `sick`, `personal` or
`other`. Field names may follow the API conventions; the handoff records the final shapes.

Append migrations with the full "Adding a table" checklist. Update the ownership map, the erasure function and the database isolation test's lists,
add the new migration to `MigrationIntegrationTest`'s list, and validate every new response with the contract validator.

## UI and UX

Follow the prototype (`schLv`, `schBl`) and the component map, as F06-S02 does: Tabs on the sheet, Field with Input for the note and label, the
component map's date control, Select for the type, `TimeSelect` for times, Item rows for the lists, Alert Dialog to confirm removing leave, Badge for
the card's leave and "N bookings to resolve", Alert for the banners, and Sonner for the saves.

| Screen | Where | Behaviour |
| --- | --- | --- |
| Leave | Schedule sheet | The banner "Customers can’t book this person on leave days. Existing bookings are kept and flagged, never cancelled for you. For part of a day, use Blocked time.", **+ Add leave** with `team.manage`, the form and the list |
| Blocked time | Schedule sheet | The banner "Blocked time is part of a day when this person can’t be booked, like training or an errand. It shows on the calendar with its label.", **+ Block time** with `team.block`, the form and the list |
| Card | Team tab | The leave badge of AC-9 |

Times use `formatClock`. Messages go through next-intl, English only in R1, with curly apostrophes. Every interactive element has a `data-testid`.

## Risks and test focus

- `data-integrity`: `TeamSchedule` is again the highest risk: a block at a window's edge, across a break, covering all that is left; leave beating a date
  change; removal restoring the answer. A booking is never touched.
- `security` and `permissions`: a leave's type and note, sick leave especially, reaching nobody without `team.manage` by any route: the schedule, the
  member list, the card, the audit and the public list. Receptionist's blocks, and a Professional and a support session refused.
- `concurrency`: overlapping leaves, overlapping blocks and both limits under simultaneous requests, deterministically; adding to someone being removed.
- `boundary`: today, a year ahead and a day beyond, one-day leave, touching blocks, the salon's date against UTC, the 100th and 101st leave, the 200th and 201st block.
- `tenant-isolation`: every table and endpoint, and `TeamSchedule`.
- `api` (contract): every response, including absent `type` and `note` and `null` `nextLeave`.
- `functional`, `responsive`, `accessibility`: both tabs, read-only roles, the handle at phone widths.

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

- The developer has checked every acceptance criterion on the running product with two synthetic salons, each role and a support session, and recorded it in the handoff.
- The verification commands are green.
- `TeamSchedule` and `MemberBookingsAffected` state the new behaviour in their Javadoc for F07 and F10.
