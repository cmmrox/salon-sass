# F04-S04 Booking rules and policies

## Objective

An Owner or Manager sets the salon's booking rules in Settings: when customers can book, the
deposit, and what a late cancellation, a no-show and rescheduling allow. A plain-language summary
shows exactly what customers will see before they pay. Each save creates a new version, so
bookings made later keep the terms they were made under. F07, F08 and F09 read these rules
through one interface.

## Read first

- `docs/brd/requirements.yaml`: BKG-02 and BKG-09.
- `features/F04-salon-setup/README.md`: saving vs publishing, policies and existing bookings, and
  the per-service deposit override.
- `features/F04-salon-setup/stages/S01-profile-and-publishing/` (spec and handoff): the Settings
  page, permissions and version handling this stage builds on.
- `docs/decisions/DECISION_LOG.md`: decisions 058, 073, 083, 085 and 089.
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Tests you must
  write".
- `docs/engineering/backend.md`: modules, provided interfaces, ownership, persistence (money as
  `bigint` minor units with a currency), "Booking integrity", tests.
- `docs/engineering/web.md`: BFF rules, "Styling" (the component map), "Tests".
- `packages/format`: `formatMoney`, for every amount shown.
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon web, Settings,
  **Booking rules** (`setRules`, `ruNum`, `ruText`).
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BKG-02 | The salon sets the slot interval, the minimum notice before an appointment, and how far ahead customers can book. | Full, as settings. F07 applies them to availability |
| BKG-09 | Policies are set per salon: deposit (percentage or amount, cap, per-service override), free-cancellation window, amount retained on late cancellation or no-show, limits on rescheduling. | Full, as settings, except the per-service override, which is set on the service in F05. F08 and F09 apply the rules |

## Scope

- **API (`booking`):**
  - the booking rules, each save a new immutable version;
  - the current rules, public;
  - the version history for settings users;
  - the provided interface `BookingPolicies`.
- **Web:** the **Booking rules** tab, with the form and "What customers see before paying".

## Out of scope

- Applying the rules: availability (F07), taking deposits and payment links (F08), cancellation,
  no-show and refunds (F09).
- The per-service deposit override field (F05). This stage's model must hold it: see "Design
  notes".
- More than one branch (F18). The rules are salon-wide in R1. The table carries `branch_id` for
  the default branch, so F18 can add per-branch rules without moving data.

## Acceptance criteria

- AC-1: An Owner or Manager opens **Settings → Booking rules**. Until the salon first saves, the
  form shows the prototype's suggested rules, marked "Not saved yet", and `GET
  /api/v1/salon/booking-rules` returns `null`. The suggested rules are:
  - start times every 15 minutes, at least 1 hour's notice, and up to 2 months ahead;
  - a 20% deposit, capped at Rs. 2,000;
  - free cancellation until 24 hours before;
  - a late cancellation keeps the deposit, and a no-show keeps the deposit;
  - rescheduling twice, up to 12 hours before.
  (BKG-02, BKG-09)
- AC-2: The booking times offer exactly the prototype's choices:
  - interval: 10, 15, 20 or 30 minutes;
  - notice: any time, 30 minutes, or 1, 2 or 4 hours, or 1 day;
  - horizon: 2 weeks, or 1, 2, 3 or 6 months, stored as 14, 30, 60, 90 or 180 days.
  The API refuses any other value with a 400 field error. (BKG-02)
- AC-3: The deposit is None, Percentage or Fixed amount:
  - a percentage must be 1 to 100, with a cap of at least Rs. 500;
  - a fixed amount must be Rs. 100 to Rs. 100,000;
  - free cancellation is 2, 6, 12, 24 or 48 hours before;
  - a late cancellation keeps nothing, the deposit, or a share of the price (1 to 100%);
  - a no-show keeps the deposit, or a share of the price (1 to 100%);
  - rescheduling is not online, once, twice or any number of times, up to 2, 6, 12 or 24 hours
    before.
  Breaking any rule is a 400 field error on that field, nothing is saved, and the screen shows the
  prototype's message beside the field. Amounts are entered and shown in the salon's currency
  through `formatMoney`, and stored in minor units. (BKG-09)
- AC-4: "What customers see before paying" updates as the user changes the form. It states the
  prototype's sentences (`ruText`) for the current values, including the cases where nothing is
  kept because there is no deposit, and "To reschedule, contact the salon" when rescheduling is not
  online. A unit test proves the sentences for every option. (BKG-09)
- AC-5: **Save booking rules** creates a new version and makes it current, and the next `GET
  /api/v1/salon/booking-rules` returns it. The previous version is unchanged and still readable by
  its id through `BookingPolicies` and the history. Saving values identical to the current version
  creates no version and is 409 `invalid_state`. A stale `version` is 409 `conflict` and changes
  nothing. When two saves of the same version are sent at once, exactly one succeeds. (BKG-09)
- AC-6: `BookingPolicies` returns the current version for the caller's salon, or a version by id
  for that salon only. Another salon's version id is "not found". Integration tests prove that a
  version read by id after three later saves still has its original values. (BKG-02, BKG-09)
- AC-7: Settings users see the history: each version's time, author and the fields that changed,
  newest first, paginated. Receptionist, Professional and Accountant see no Booking rules tab and
  are refused the settings endpoints with 403 `permission_denied`. They can still read the public
  current rules. (BKG-09)
- AC-8: Tenant isolation and states:
  - another salon's rules and versions are never readable or changeable;
  - with no tenant context the new tables return no rows;
  - a support session reads the tab and the history and is refused saving with 403
    `support_read_only`;
  - suspended and read-only salons answer as the other settings endpoints do.
  (BKG-02, BKG-09)
- AC-9: Every save is recorded in the salon's audit trail with actor, time, the version created and
  the names of the changed fields. (BKG-09)
- AC-10: The Booking rules tab provides the following at every width
  `docs/design/ui-ux-standards.md` names:
  - loading, saved, field-error, conflict, unavailable and "Not saved yet" states;
  - the leave-with-unsaved-edits guard;
  - keyboard-only use, with accessible names for the deposit choice and every select;
  - no WCAG 2.2 AA violation in the accessibility check.
  (BKG-02, BKG-09)

## Design notes

- **Module (decision 089).** `booking` owns the rules table, with `tenant_id` and `branch_id`, and
  checks the branch through `branding`'s `SalonBranches`. It reads the salon's currency through
  `platform`'s provided interface, LKR when unset. Update `booking`'s `allowedDependencies`
  accordingly. `booking` will later depend on `staff` (`OpeningSchedule`); nothing may depend on
  `booking` except where `docs/engineering/backend.md` allows.
- **Versions.** One row per version: immutable, with its number, author and creation time. The
  current version is the newest. No update or delete grant on the table for the application role.
- **The per-service override.** The deposit rule is a value type: none, percentage with cap, or
  amount. F05 can store one on a service and `BookingPolicies` can resolve "the service's rule if
  set, otherwise the salon's" without changing this table. Write that resolution method now, with
  the service's rule passed in, and test it.
- **Summary text.** The customer-facing sentences are composed on the client from the structured
  rules, with translatable messages, so that the later customer app produces the same sentences.
  The API returns structured rules only.
- **Money.** Rupee amounts are entered as whole rupees, stored as minor units, and shown with
  `formatMoney`. Percentages are whole numbers.

## API and data changes

Routes are `booking`'s, under `/api/v1/salon/booking-rules`, on the salon's workspace host.

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/salon/booking-rules` | none (public) | The current `rules` with `versionId` and `currency`, or `null` |
| `GET /api/v1/salon/booking-rules/settings` | `settings.read` | `{version, rules or null, currency}`, where `version` is the current version number (0 before the first save) |
| `PUT /api/v1/salon/booking-rules/settings` | `settings.manage` | `{version, rules}` -> 200 as above; 400 field errors; 409 `conflict` or `invalid_state` |
| `GET /api/v1/salon/booking-rules/history?page=` | `settings.read` | `{items: [{versionId, version, createdAt, createdBy, changedFields}], page}` |

`rules` is:

```json
{
  "slotIntervalMinutes": 15,
  "minimumNoticeMinutes": 60,
  "bookingHorizonDays": 60,
  "deposit": { "kind": "percentage", "percent": 20, "capMinor": 200000 },
  "freeCancellationHours": 24,
  "lateCancellation": { "kind": "deposit" },
  "noShow": { "kind": "deposit" },
  "reschedule": { "limit": 2, "untilHours": 12 }
}
```

The other forms are:
- `deposit`: `{kind: "none"}` or `{kind: "amount", amountMinor}`;
- `lateCancellation`: `{kind: "nothing"}` or `{kind: "share", percent}`;
- `noShow`: `{kind: "share", percent}`;
- `reschedule.limit`: `0` (not online, `untilHours` null), `1`, `2` or `null` (any number of
  times).

Append a migration with the full "Adding a table" checklist, including `branch_id`. Add the table
and path to the ownership map, and validate every new response with the contract validator.

## UI and UX

Follow the prototype (`setRules`) and the component map:
- Select for every list choice, with the prototype's labels;
- Toggle Group for the deposit kind (No deposit, Percentage, Fixed amount);
- Field with Input for the numbers, with their suffix (`%`, `Rs.`);
- Card for "What customers see before paying";
- Item rows in a Sheet for the history;
- Button, Spinner, Alert and Sonner.

| Screen | Route | Behaviour |
| --- | --- | --- |
| Booking rules | `/workspace/settings/rules` | "What customers see before paying" with "Changes apply to new bookings. Existing bookings keep the policy they were made under." Booking times, Deposit (with "A service can ask for a different deposit, set on the service."), Cancelling and no-shows, Rescheduling. **Save booking rules**, disabled while a field is in error. **History** opens the version list |

The tab bar now shows Profile, Hours & closures, Booking rules and Branding in the prototype's
order. Messages go through next-intl, English only in R1. Every interactive element has a
`data-testid`.

## Risks and test focus

- `data-integrity`: versions immutable; a version by id unchanged after later saves; no version
  for an identical save; minor-unit conversion with no rounding.
- `validation`: every allowed value and every boundary (0, 1, 100, 101%; Rs. 99, 100, 100,000,
  100,001; cap 499, 500).
- `concurrency`: stale versions; two saves at once.
- `tenant-isolation` and `permissions`: the table and endpoints; the public read; roles and support.
- `contract`: `null` before the first save, the deposit and retention variants.
- `functional`, `responsive`, `accessibility`: the form, the summary sentences, the history sheet.

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

- The developer has checked every acceptance criterion on the running product with two synthetic
  salons, each role and a support session, and recorded it in the handoff.
- The verification commands are green.
- `BookingPolicies`, including the per-service resolution method, is documented in its Javadoc as
  the contract F07, F08 and F09 use.

## Corrections at close

Recorded by the lead when the stage closed. No requirement changed:
- **History shape.** `GET /api/v1/salon/booking-rules/history` takes `page` and `size` and answers
  `{items, page, size, total}`, as the other paged lists do. Each item carries `createdBy` (the
  staff account id) and `createdByLabel` (decision 091); the sheet shows "You", the label, or
  "A team member".
- **Public read.** `{versionId, currency, rules}`, or a JSON `null` body before the first save.
- **Money.** Amounts must be whole units of the currency (400 otherwise). The limits are in LKR
  and scale by the currency's fraction digits. A cap has no business maximum, only the
  safe-integer bound (decision 091).
- **`booking`'s dependencies** also include `identity`, for `Principals` and `StaffLabels`.
- **Integer choices** are listed in the OpenAPI field descriptions rather than as enums.
- **A save that changes nothing** shows "Nothing changed: these are already your booking rules."
- **Layout.** "What customers see before paying" is above the form on phones, as in the prototype.
- **Leave guard.** A Settings tab change with unsaved edits now asks first, on Booking rules and
  (fixed in this stage) on Profile and Branding.
