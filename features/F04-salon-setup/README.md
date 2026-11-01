# F04 Salon setup: profile, branding, hours and policies

## What the customer gets

A salon owner or manager sets up the salon from one Settings page with six tabs. Profile, Hours &
closures, Booking rules and Branding are delivered here; Business and Branches arrive with F18.

- **Profile and Branding** are drafts until published. Once published, everything that reads the
  salon (the staff workspace now, the customer app later) shows them without an app release.
- **Opening hours** are set per weekday.
- **Holidays** come from the operator, who publishes the Sri Lankan public holiday and Poya calendar
  each year. Each salon decides what it does on each day.
- **Special closures** close the salon for a day and list the bookings that day already has; none is
  cancelled.
- **Booking rules** set when customers can book, the deposit, and what a late cancellation or no-show
  keeps. A plain-language summary shows exactly what customers will see before they pay.

## Requirements

- Profile and its delivery from the API: BRD-02, BRD-04
- Branding: BRD-01
- Hours, holidays and closures: STF-04
- Booking times and policies: BKG-02, BKG-09

NFR-13 (formats, phone numbers, time zone and currency are configuration) applies throughout.

## How it is built

- **Surfaces:** API and staff web, plus one card in the operator console (the holiday calendar).
- **Design:** the reference is the prototype's Settings tabs and the console's Holiday calendar
  card, drawn under CR-023 and confirmed by the owner before planning (decision 070,
  `docs/design/ui-ux-standards.md`).
- **Modules:**
  - `branding` already serves the published brand (`PublishedBrandService`). It gains drafts,
    publishing and the business profile.
  - Opening hours and closures belong to `staff` (the STF module of the register).
  - Booking times and policies belong to `booking`.
  - The holiday calendar is platform reference data, published by the operator, with no
    `tenant_id`. A salon's choices about each day are tenant data (decision 078,
    `docs/architecture/multi-tenancy.md`).
- **Branch scope:** every new table carries `branch_id`, and each salon gets its one default branch
  here (decision 073).
- **Policies and existing bookings:** a policy is versioned. A booking will keep the version it was
  made under, so a later change never rewrites an existing booking's terms.
- **Closures before bookings exist:** a closure is recorded, and the bookings it affects are
  answered through a provided interface that `booking` implements when F07 builds bookings. Until
  then the list is empty. F07 and F10 (BKG-13's attention list) must consume closures.
- **Depends on:** F02 (sign-in and roles: only the Owner and Manager change settings).

## Stages

The order and status of the stages is in `features/README.md` (generated). This section explains
why the feature is divided the way it is.

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F04-S01 | The Settings page with its tabs, the default branch, and the Profile tab (contact, WhatsApp, address and map position, description, social links). The profile is a draft until published, and the API serves the published profile. | It sets the draft-and-publish pattern and the branch scope that the other three stages build on, and it proves BRD-04 delivery early. |
| F04-S02 | The Branding tab: name, tagline, logo, cover, brand colour with automatic readable text, live preview, publish and reset. | Image upload and storage, and the colour-contrast rule, are their own risks. The tenant brand already exists from F03, so this extends a live record. |
| F04-S03 | Weekly opening hours; the operator's holiday calendar (import, check, publish) with each salon's review; special closures listing the bookings they affect. | The riskiest stage: platform reference data copied into tenant data, time zones, a cross-module interface to bookings, and a console screen. |
| F04-S04 | The Booking rules tab: start-time interval, minimum notice, how far ahead, deposit (percentage with cap, or a fixed amount), free-cancellation window, what a late cancellation or no-show keeps, reschedule limits, and the plain-language customer summary. | These rules are read by F07 (availability), F08 (deposits) and F09 (cancellation). It needs versioning and a clear contract before those features start. |

## Feature-level test focus

- **Journey:** a new salon's owner completes Profile, Branding, Hours and Booking rules, publishes,
  and the API serves exactly what was published. Unpublished drafts are never served.
- **Isolation:** another salon's staff, and the operator outside a support session, can neither read
  nor change any of it. Each salon's holiday choices stay its own.
- **Roles:** only the Owner and Manager change settings. Other roles can read what they need or are
  refused.
- **Holidays:** a calendar the operator publishes reaches every salon as "To review" and changes
  nothing until the salon accepts it.
- **Closures:** closing a day is reflected in the published opening information.
- **Policies:** changing a policy creates a new version and leaves the previous one intact. The
  customer summary matches the saved rules.
- **NFR-13:** Sri Lankan phone validation, rupee amounts and the salon's time zone come from
  configuration.

## Decisions and open questions

- **Saving vs publishing.** Hours, closures and booking rules save immediately and apply to new
  bookings. Profile and Branding are drafts until published, because customers see them. This is
  drawn this way in the prototype.
- **Per-service deposit override (part of BKG-09).** The salon-wide rule is set here. The override
  is set on a service, so its field is built in F05; F04's rule model must allow it.
- **Holiday dates in the prototype are sample data.** Real dates come only from the operator's
  published calendar.
- **Settings changes in the Activity log (open, owner to decide).** F04-S01 writes every save,
  publish and discard to the salon's audit trail. The workspace Activity log (F02) lists only
  sign-in, staff and support actions, so these entries are recorded but not shown there. Showing
  them changes a delivered screen and would go through a change request.
- **Cost of the public salon call.** The proxy calls `GET /api/v1/salon` on every salon request.
  Since F04-S01 that call also reads the published profile: up to four more queries once a salon
  has published. That is acceptable at R1 volumes; a cache is a later refactor if measurement
  shows a need.
- **Recorded at F04-S01 close.** `publishedBy` is the publishing staff account's id. Before the
  first publication, "unpublished changes" means anything set, so publishing an empty profile is
  409 `invalid_state`. Phone numbers display in libphonenumber's national format, for example
  `0112 504 417` for a Colombo landline; the prototype now shows the same.
- **Recorded at F04-S02 close.** The brand and the profile share one draft component. The publish
  bar and leave dialog keep their F04-S01 test ids (`profile-publish` and the rest) on both tabs,
  so QA's existing tests keep working. QA should scope those ids by tab.
