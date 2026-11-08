# F06 Staff, schedules and leave: product review

Reviewed as: a salon owner or manager setting up the team and its hours at the end of the day (desktop
at 1280 and a small Android phone at 360), a receptionist at the front desk blocking a stylist's time
on a busy Saturday (tablet at 768), a professional reading their own week between clients (phone), and
the operator in a support session. Customers meet the team only through the public team API in R1;
their screens are F15's.
Compared with: Fresha, from its public help centre, read 2026-11-09 (pages listed under "Compared with
other products"). Booksy's and Vagaro's help centres refused automated reads (HTTP 403) on that date, so
neither is compared here.

## Test approach

| Type | Applies because | Ruled out because |
| --- | --- | --- |
| `functional` | The Team page (Team and Sign-in accounts tabs, cards, empty state), the profile sheet with its service picker and photo, and the schedule sheet's four tabs (Weekly hours, Date changes, Leave, Blocked time) | - |
| `responsive` | Those screens at the six widths of `docs/design/ui-ux-standards.md`, with long names, notes, labels, five breaks and six leaves and blocks; the four schedule tabs scroll inside their own rail on a phone, as the S02 spec and the prototype's chips have it, and each is proved reachable | - |
| `accessibility` | Axe (WCAG 2.2 AA) in each state for an Owner and a read-only role, accessible names by weekday and break, the photo control and each category's Choose, keyboard-only paths with a visible focus | - |
| `api` | Every new endpoint under `/api/v1/team`: every boundary of every field, every documented error code, the contract validator on every response | - |
| `tenant-isolation` | Every new endpoint and table (members, services, photos, weeks, date changes, leave, blocks): another salon's ids answer as unknown ids by every method, photos only on their own hostname, the public team list never crosses, no rows with no tenant context, and an operator outside a support session reaches nothing (TC-F06-002) | - |
| `permissions` | Each of the five roles, signed out, a customer and a support session on every endpoint; `team.read`, `team.manage` and `team.block`; who receives a sign-in, a date change's note, a leave's type and note; suspended and read-only salons | - |
| `security` | Photo uploads (decoded, re-encoded without metadata, size and pixel limits, checked before the bytes are read); privacy of leave: sick leave's type and note reach no one without `team.manage` by any route, including what a browser receives and the audit rows; the public list carries no sign-in, version or hidden person | - |
| `concurrency` | One case per invariant: the last staff seat under a member and an account requested together, both orders, and behind a plan move; two saves of one version (profile and week); two links of one account; two changes on one date; two overlapping leaves; two overlapping blocks; the 100 leave and 200 block limits under a burst | - |
| `data-integrity` | Seats counted row by row (accounts plus unlinked members) in usage and the plan move; a person's services worked out when read (a moved or deleted service, a whole category gaining one); a week copied once from the salon's hours and never linked after; audit rows with actor, time and before and after, never a note or a leave's type | - |
| `resilience` | - | No provider or background job a user waits on is added. The browser's "unavailable" state is covered as a functional state (aborted data calls) |
| `load` | - | No endpoint here has a response-time target in the requirements or carries a booking yet; availability, which reads `TeamSchedule`, is F07's |
| `branding` | - | F06 changes no brand; the screens use the existing theme |
| `mobile` | - | No customer-app screen in F06 (F15) |
| `payment-integrity` | - | Nothing is charged |
| `build` | - | No criterion about the build or the tooling |

Deliberate narrowing, as the specs and the catalogue allow: a rule proved at the API is not proved
again in the browser, and a journey asserts what passes between the stages, not each stage's rules.
Java interfaces with no route are the developer's integration tests until their first caller from
outside arrives with F07: `TeamMembers` (S01 AC-4), `TeamSchedule` (S02 AC-8 in part and AC-9, S03
AC-7) and the refusals and lists that need a booking (`UpcomingMemberBookings`,
`MemberBookingsAffected`: S01 AC-7's refusal and AC-8, S02 AC-11, S03 AC-8's list and AC-3's count).
From outside, QA checks what a client can observe: the stored week, changes, leave and blocks; the
empty answers; and the sheet's words and lists from real requests whose answers gain a booking in the
browser.

Deferred, each agreed in its stage and listed in `qa/coverage.md`: TC-F06-S01-011, -012, -013,
TC-F06-S02-028, -029, TC-F06-S03-030, -031 and the journey TC-F06-004, all unblocked by F07. F07 has not
started, so none was run.

The audit criteria (S01 AC-15, S02 AC-15, S03 AC-12) are asserted on the stored `audit_entry` rows: the
audit list a support session reads (`GET /api/v1/identity/audit`) shows no `staff.*` entry at all, a gap
the lead has raised with the owner (S03's corrections at close).

New critical checks were shown to fail on a realistic violation before they were trusted, never by an
edit to the product or an inverted expectation:
- the seat race and the plan move (TC-F06-S01-034) failed 13 of 13 times on the build before V29
  (BUG-F03-008, at the S01 dry run);
- the overlapping-leave race (TC-F06-S03-004) and the privacy of a leave's type (TC-F06-S03-021), run
  once each through a local proxy in front of the QA API that answered a refused overlap with 201, or
  added `type` to a schedule read, failed with "exactly one was kept (201,201)" and "a Receptionist:
  exactly the days of the leave". The proxy was stopped and the cases pass against the product. The
  block race and both limits use the same mechanism (requests proved in flight together, the invariant
  read back) and were not proved separately.

## Verdict

Good enough to put in front of salon owners and their front desks. A manager can add a person
with no sign-in, choose what they perform, set a week with breaks, take out a half day, record
leave and block an hour, and every screen says what will happen to bookings ("kept and flagged,
never cancelled for you"). Privacy holds: a receptionist sees "On leave" and the days, never that
it is sick leave, by any route. No product defect was found in this pass. The improvement that would
help most is making one-day leave quicker to record: the last day starts empty and its calendar
offers days before the first.

## What works well

- The Team page and its cards follow the prototype closely: avatar with a coloured initial, title,
  Services, Hours ("Mon–Sat, 07:15 – 18:00"), the Shown badge, Sign-in, and the leave badge in the
  prototype's warning pill. The empty state invites the first person and says they need no sign-in.
- A new person starts on the salon's opening hours, as Fresha's new team members do, so the common
  case needs no schedule work at all.
- The schedule sheet keeps one place for the week, single dates, leave and blocked time, with a short
  banner on each tab saying what it does to bookings. This is clearer than Fresha, whose help pages do
  not say what happens to appointments inside time off.
- Leave's privacy is right by design: the type and note reach only Owner and Manager, everyone else
  sees "On leave" with the days, and the audit rows hold neither.
- A receptionist can block time without a manager, as decision 099 intended, and cannot touch leave.
- The forms worked by keyboard alone, focus came back to the card's Schedule button on closing, and
  the phone handle is now visible on every sheet (decision 103).

## Suggestions

Screenshots named below are in `qa/reports/20261109-031920-F06-ca7f75ae348c/review/` (reports are not
committed), taken 2026-11-09 on the QA instance at a27b497 and on the prototype served locally.

Ranked, most valuable first. A finding that breaks a requirement is a bug, not a suggestion: file it.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F06-01 | should | One-day leave in fewer steps: once the first day is chosen, set the last day to it and open the last-day calendar at it, offering no earlier day || CR-026 |
| SUG-F06-02 | should | Leave the year out of dates within the current year on the card badge and the leave and block rows || CR-026 |
| SUG-F06-03 | should | Do not show "Name must be 2 to 40 characters." on a blank Add team member sheet before anything is typed or saved || CR-026 |
| SUG-F06-04 | idea | Repeating blocked time (a weekly training slot) || backlog |
| SUG-F06-05 | idea | Week patterns that repeat every two to four weeks (rotas) || backlog |
| SUG-F06-06 | idea | When a date control's calendar opens, put focus on the chosen day or today, not on "Go to the Next Month" || backlog |

### SUG-F06-01 One-day leave in fewer steps

- **Observed:** Schedule, Leave, + Add leave. After choosing a first day the last day still reads
  "Choose a day". Its calendar opens on today and offers every day from today, including days before
  the chosen first day, so "The last day can’t be before the first." can be reached and is only said
  on pressing Add leave.
- **Why it matters:** the commonest leave a front desk records in the morning is one sick day. Today
  that is open, pick, open, pick the same day again, then Add leave; a manager at the end of a long
  day can also pick a last day before the first and only learn it on saving.
- **Instead:** when the first day is chosen and the last day is empty or earlier, set the last day to
  the first day; open the last-day calendar on the first day's month and disable the days before it.
  The API's rule stays as it is.
- **Evidence:** observed at 1280 on alpha as a Manager (`ours-leave-form-1280.png`,
  `ours-leave-list-1280.png`). Fresha's time off takes a start date and
  a repeat end date, so a single day is one date ([Manage time off](https://www.fresha.com/help-center/knowledge-base/calendar/21-manage-time-off-for-your-team-1), read 2026-11-09).

### SUG-F06-02 Leave the year out of this year's dates

- **Observed:** the card badge reads "Sick leave, Thu, Nov 12, 2026 to Sat, Nov 14, 2026" and wraps
  to two lines in a 1280 card; the leave row and block rows wrap the same way on a phone. The
  prototype's "Annual leave, Thu 12 Nov to Sat 14 Nov" fits one line.
- **Why it matters:** leave and blocks are at most a year ahead, so the year is almost always this
  one; the badge is read at a glance at the front desk.
- **Instead:** a short form of `formatDay` without the year when the date is in the salon's current
  year, used product-wide. Decision 106 chose `formatDay` as it prints over a second format, so this
  is the owner's call, not a defect.
- **Evidence:** `ours-leave-list-1280.png`, `ours-receptionist-leave-768.png` against
  `proto-team-1280.png`.

### SUG-F06-03 No error on a blank sheet

- **Observed:** + Add team member opens with "Name must be 2 to 40 characters." in a red alert and the
  Name field outlined red, before anything is typed.
- **Why it matters:** an owner adding their first person meets an error first. The prototype does the
  same (`stErr` is painted on open), so the build follows the design.
- **Instead:** show the rule once the field has been left or Add to team pressed, keeping Add to team
  disabled while the name is empty.
- **Evidence:** `ours-profile-sheet-1280.png`.

### SUG-F06-04 Repeating blocked time

- **Observed:** a block is part of one date. A weekly training slot means one block per week, up to
  200 upcoming.
- **Why it matters:** recurring commitments (training, school runs) are the main use of blocked time.
- **Instead:** a repeat (weekly, until a date) on the block form, stored as single blocks or a rule.
  Out of scope by the S03 spec ("Repeating blocked time"), so a backlog item.
- **Evidence:** Fresha's blocked time repeats daily, weekly, monthly or on a custom schedule, and
  editing asks "this block only / this and future / all" ([Create and manage blocked time](https://www.fresha.com/help-center/knowledge-base/calendar/101628-create-and-manage-blocked-time-in-your-calendar), read 2026-11-09).

### SUG-F06-05 Rotas that repeat every few weeks

- **Observed:** a person has one week; alternating weekends need a date change for every Saturday.
- **Why it matters:** salons with weekend rotas.
- **Instead:** a week pattern of two to four weeks. Out of scope by the S02 spec, so a backlog item.
- **Evidence:** Fresha's shifts repeat every week or every 2, 3 or 4 weeks ([Schedule and update team shifts](https://www.fresha.com/help-center/knowledge-base/calendar/252-schedule-and-update-team-shifts), read 2026-11-09).

### SUG-F06-06 Calendar focus on a day

- **Observed:** with the keyboard, Enter on First day, Last day or a block's Date opens the calendar
  with focus on "Go to the Next Month"; one Tab more reaches today. Keyboard-only use works.
- **Why it matters:** one extra step for keyboard and screen-reader users on every date.
- **Instead:** focus the selected day, else today, when the calendar opens.
- **Evidence:** observed on the Leave form at 1280.

## Compared with other products

| Aspect of this flow | Ours | Others | Better for our users |
| --- | --- | --- | --- |
| Bookable without a login | Yes, "No sign-in. They can still take bookings" | Fresha: a profile with "No access", bookability a separate setting | Same |
| Hidden from online booking | "Show for online booking" on the profile | Fresha: "Exclude team member from online bookings" | Same |
| Services a person performs | Whole categories (including services added later) or single services; none by default | Fresha: single services or "All services"; all by default | Ours for a growing menu; theirs for a first setup |
| New person's hours | Copied from the salon's opening hours | Fresha: the location's opening hours | Same |
| Breaks | Up to 5 per day, inside the hours | Fresha: several shifts a day, up to 10 | Same |
| Repeating weeks | One week | Fresha: every 1 to 4 weeks | Theirs (SUG-F06-05) |
| Time off | Whole days, four fixed types, private note | Fresha: start and end times, salon-defined types, description, "Approved" | Ours for privacy and simplicity; theirs for part days, which we cover with blocked time |
| Bookings inside time off | Kept, listed before saving, flagged "N bookings to resolve" | Fresha: not stated in its help pages | Ours |
| Blocked time | One date, a label, receptionists may add | Fresha: titled presets with paid or unpaid, repeating | Theirs for repeats (SUG-F06-04) |
| Seats | One per person, with or without a login | Fresha: price per bookable team member | Same |

Fresha pages read on 2026-11-09:
[Add team members](https://www.fresha.com/help-center/knowledge-base/team/276-add-team-members-to-your-workspace),
[Schedule and update team shifts](https://www.fresha.com/help-center/knowledge-base/calendar/252-schedule-and-update-team-shifts),
[Update team member details](https://www.fresha.com/help-center/knowledge-base/team/102281-update-team-member-account-details),
[Manage time off](https://www.fresha.com/help-center/knowledge-base/calendar/21-manage-time-off-for-your-team-1),
[Types of time off](https://www.fresha.com/help-center/knowledge-base/calendar/582-create-and-manage-types-of-time-off),
[Blocked time](https://www.fresha.com/help-center/knowledge-base/calendar/101628-create-and-manage-blocked-time-in-your-calendar),
[Blocked time types](https://www.fresha.com/help-center/knowledge-base/calendar/18-set-up-and-manage-blocked-time).

## Design comparison

Compared with the prototype (`aStaff`, `stOpen`, `schLv`, `schBl`) at 1280, 768 and 360, served
locally from `docs/design/prototype/index.html`. The Team page, card, profile sheet, schedule sheet
and both new forms follow its composition, typography, colours and controls. Differences, each
following a spec or ruling: the 24-hour clock (decision 103), `formatDay` dates (decision 106), the
schedule tabs scrolling inside their rail on a phone where the prototype wraps them on desktop (both
do that here at 1280), Checkbox and Switch for the picker and online booking (the component map), and
no owner badge, consultations, price premium or branches (out of scope).

## For the lead: documents that differ from the build

- `docs/architecture/identity-and-access.md`, "Implemented web boundary", says the web BFF accepts only
  `/identity-web/staff/{action}`; F06's handoffs list team actions under `/identity-web/{kind}/`
  (`team-members`, `team-member-add`, ..., `team-leave-add`, `team-block-add`, `team-schedule`) and the
  `/team-photos/{id}` route, which the section does not name.
- The support session's audit list shows no `staff.*` entries (S03's corrections at close); the audit
  criteria rest on stored rows until the owner decides.

## Bugs filed from this review

- None. The feature run's failures were test defects (see `feature.yaml`'s evidence), and exploring
  found no departure from a criterion.
