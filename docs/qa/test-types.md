# Test types

Which kinds of test a feature of this product needs, how much of each is enough, and what each must
assert. QA tests a feature once, when all its stages are closed. How to use the table (when to add
to the specs' lists, when a type may be ruled out, where to stop) is in the `salon-qa` skill,
`references/test-design.md`.

Go down the selection table: every row whose trigger is true for any stage of the feature applies.
The `type` values are the ones the validator accepts (`qa/scripts/sdlc_lib/schema.py`).

## Contents

- Selection table
- Always required
- How much of each
- What each type must assert
- Types that need the owner's go-ahead

## Selection table

| A stage of the feature... | Type | Tool |
| --- | --- | --- |
| adds or changes a screen on the web | `functional`, `responsive`, `accessibility` | Playwright browser |
| adds or changes an endpoint | `api` | Playwright API |
| stores, reads or returns salon data | `tenant-isolation` | Playwright API |
| has anything only some roles may do | `permissions` | Playwright API and browser |
| lets two people act on the same thing (a slot, a hold, a booking, a refund, a waitlist offer) | `concurrency` | k6, or a Playwright API test firing requests together |
| has an endpoint with a response-time target, or one every booking goes through | `load` | k6 |
| takes, refunds or records money | `payment-integrity` | Playwright API with the payment sandbox |
| shows a salon's brand, name, logo or colour | `branding` | Playwright browser, three salons |
| adds or changes a screen in the customer app | `mobile` | Maestro |
| handles sign-in, one-time codes, sessions, exports or personal data, or returns errors to a caller | `security` | Playwright API |
| changes stored data, a migration, a calculation or a report | `data-integrity` | Playwright API, plus a database check through a script |
| depends on an external provider, a background job, or another service that can be down | `resilience` | Playwright API with the provider fake set to fail or delay; a shell check that stops the service |
| has a criterion about the build, the tooling or the pipeline rather than the running product | `build` | Shell check |

## Always required

- **Every acceptance criterion** has at least one case, including a negative one where the
  criterion can be violated.
- **`tenant-isolation` for every new endpoint and every new kind of record that holds or returns
  salon data.** Where it applies it is never skipped, never deferred and never trimmed. A feature
  with no salon data at all says so in its review's test approach.
- **`permissions` for every endpoint that is not public.** Check each role, not just one allowed and
  one refused.

## How much of each

The feature waits for its QA pass, so each type has a size. These came from F01-S01 and F01-S02,
whose two stages were given 108 cases between them. Going beyond a row needs the lead's agreement;
going below "Always required" is never allowed.

| Type | Enough | Not needed |
| --- | --- | --- |
| `api` | Per endpoint: one case for the contract (status, error `code`, fields and types), one per documented error. Malformed and hostile input is one case over a table of inputs | A case per malformed value; the same rule proved again through the browser |
| `tenant-isolation` | Per endpoint and per kind of record: the other salon's data cannot be read or changed. Once per feature, the same at the database as the application's role. One case that requests for different salons sent together never cross | Nothing is trimmed here |
| `permissions` | Per protected action: each role, allowed or refused | - |
| `concurrency` | One case per invariant (one slot, one booking; one payment, one confirmation) | The same race at several sizes |
| `load` | Only an endpoint with a response-time target in the requirements, or one every booking goes through. One scenario each | Load on an endpoint with no target that carries no booking |
| `resilience` | Per dependency, one case that it is down and one that it is silent: what the caller is told, inside the requirement's limit, and that the product recovers without a restart. The recovery limit is counted from when the dependency is actually back | The same outage at several lengths; a limit counted from the test's own command |
| `security` | What the trigger names for the feature (sign-in limits, sessions, exports, what an error reveals), one case each | Probing what the framework already refuses, unless an acceptance criterion asks |
| `data-integrity` | One case per migration, calculation or report the feature changes | Re-checking a constraint the developer's integration tests already prove, beyond one case from outside |
| `build` | One case per acceptance criterion about the build or the tooling | A case per way the same check could be broken |
| `functional`, `responsive`, `accessibility`, `branding`, `mobile` | Per screen: the path, its loading, empty and error states, and the widths and salons the design standards list | The same state on every screen that shares one component |

## What each type must assert

**functional.** The whole path a user takes, not one click. Loading, empty and error states. Input
at the boundaries: nothing, the minimum, the maximum, too much, the wrong kind. Back, refresh and a
second tab in the middle of a flow.

**api.** Status code, the stable error `code`, and the body's fields and types against the
contract. Validation errors name the field. Lists paginate. A repeated request with the same
idempotency key returns the first result and causes nothing twice.

**tenant-isolation.** Signed in to salon `alpha`, with an id that belongs to `beta`: read, update,
delete, list and search each return "not found" or omit the record, and never a different error
that reveals it exists. Creating a record that points at a `beta` record is refused. The same
through exports, reports, file links and anything cached. Assert the count of `beta` records
visible is zero, having first asserted `beta` has records.

**permissions.** For each role, each action is allowed or refused as the requirement says. A
refused action changes nothing. A Professional sees their own calendar and customers only. A
removed staff member's session stops working immediately.

**concurrency.** Fire the same action many times at once and assert the invariant, not the
responses one by one: exactly one booking exists for the slot; the rest received `slot_taken`;
no orphaned hold or payment is left behind. See `docs/qa/automation.md`.

**load.** The target from the requirement (for example availability search under 500 ms at the 95th
percentile), at a stated number of users, on a stated amount of data. See `docs/qa/automation.md`.

**payment-integrity.** The booking is confirmed by the gateway's server notification, not by the
return to the browser. A notification delivered twice confirms once. A notification with a wrong
signature, amount or merchant is rejected and logged. Failed and cancelled payments keep the slot
held. The amount charged equals the amount shown. A refund never exceeds what was paid.

**responsive.** At every width in the matrix in `docs/design/ui-ux-standards.md`: no horizontal scroll, nothing clipped or
overlapping, every action reachable. With a long list and with an empty one.

**accessibility.** An automated scan with no serious violations, then by hand: the whole flow by
keyboard, a visible focus, labels on inputs, errors announced, contrast on each salon's brand colour.

**branding.** The same screen for `alpha`, `beta` and `gamma`: the right name, logo and colour
each; readable text on a dark and on a light brand colour; no trace of another salon anywhere,
including the page title, emails and receipts. A draft brand change is not visible to customers.

**mobile.** The flow on a real emulator or device. Native behaviour named in the handoff (push,
deep links, calendar, permissions). Slow and lost connection. Larger system text. Returning to the
app after it was in the background.

**security.** Rate limits on sign-in and one-time codes hold. A code cannot be reused or guessed
across accounts. A session ends when it should. An export is permission-checked and audit-logged.
Nothing sensitive appears in a response, a URL or a log.

**data-integrity.** Totals equal the sum of their rows, checked row by row and not only at the
total. A change to a price or a policy affects future bookings only. A migration leaves existing
data correct. Times are right across midnight and for a salon in another time zone.

**build.** The check exits non-zero when the thing it guards is broken, proved on a copy outside
the repository, and exits zero on the committed tree.

**resilience.** With the provider failing or slow: the user is told plainly, nothing is lost or
duplicated, the work is retried, and recovery needs no manual repair.

## Types that need the owner's go-ahead

Run these only when asked: a load or stress test against anything other than the local machine, a
payment against a live merchant account, a message to a real phone, a penetration test.
