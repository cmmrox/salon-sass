# F05-S01 Categories and services

## Objective

The salon's staff get a **Services & pricing** page. The Owner and Manager keep the salon's
categories and services there: create, edit, duplicate, hide or show online, and delete when no
upcoming booking uses the service. Every price, time or buffer change is saved as a new version of
the service's terms, stored as segments, so F07 can book against a version that never changes.
Anyone can read the salon's bookable menu through a public API, which the customer app (F15) uses.

## Read first

- `docs/brd/requirements.yaml`: CAT-01, CAT-02, CAT-04.
- `features/F05-service-catalogue/README.md`: scope inside the prototype's service sheet,
  segments, versioned terms, the guard through `booking`.
- `docs/decisions/DECISION_LOG.md`: decisions 073, 074, 083, 085, 086, 087, 089, 092 and 093.
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Tests you must
  write".
- `docs/architecture/identity-and-access.md`: "Role permissions" (`catalogue.read`,
  `catalogue.price.change`, support sessions).
- `docs/engineering/backend.md`: modules, "Provided interfaces", "Ownership of tables and paths",
  "Persistence and migrations" (money as `bigint` minor units with a currency), "Booking
  integrity" (segments), "Security" (uploads), "Tests".
- `features/F04-salon-setup/stages/S02-branding-draft-and-preview/spec.md`: how brand images are
  checked, stored and served, which service photos follow.
- `docs/engineering/web.md`: BFF rules, "Styling" (the component map), "Tests".
- `packages/format`: `formatMoney`, for every amount shown.
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon web, **Services**
  (`aSvc`, the F05 block's `svTabs`, `catTab`, `ceOpen`, `catDel`), the service sheet (`sePaint`,
  `seErr`, `seWarn`, `seSave`, `seDel`, `seDup`, `seToggle`, and the F05 additions to `sePaint`).
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| CAT-01 | A service has a name, description, category, price, duration, buffer, optional image, and a setting for whether it can be booked online. Staff can create, edit, duplicate (the copy starts hidden), and hide or show a service online. A service can be deleted only when no upcoming booking uses it; otherwise it can only be hidden. | Full. The delete guard asks `booking` through an interface that answers "none" until F07 |
| CAT-02 | Categories are defined and ordered by the salon. | Full |
| CAT-04 | Changes to price, duration or buffer apply to future bookings only. Existing bookings keep the terms they were booked on. | Full on the catalogue side: immutable terms versions. F07 stores the version on each booking |

## Scope

- **API (`catalogue`):** categories; services; terms versions with segments; service photos; the
  public menu; the provided interface `ServiceTerms`; the declared interface
  `UpcomingServiceBookings` with an implementation that answers "none".
- **Identity:** the new permission `catalogue.read` (decision 093).
- **Shared:** the image checks move from `branding` into `shared`, with branding's behaviour and
  tests unchanged.
- **Web:** the **Services** navigation entry and the Services & pricing page with the **Services**
  and **Categories** tabs, the category sheet and the service sheet.

## Out of scope

- Variants, "From" prices, Popular and a service's own deposit (F05-S02). The data model must allow
  variants without moving data: see "Design notes".
- The Products tab and Settings → Tax & charges (F05-S03). The Products tab is not shown yet.
- Pricing kinds ("from", consultation), option groups, add-ons, processing time and patch tests
  (F17), and service lines and the per-branch view (F18). The service sheet shows none of their
  controls.
- Booking against a service, and showing the bookings that use it (F07).

## Acceptance criteria

- AC-1: Every salon starts with the prototype's ten categories, in this order: Cuts & styling,
  Colour, Facials & skin, Nails, Brows & lashes, Waxing, Makeup & draping, Massage & spa, Bridal,
  Consultations. This holds for a salon provisioned after this stage and for every existing salon
  after the migration. A salon that renames, reorders or deletes them keeps its changes. (CAT-02)
- AC-2: On the **Categories** tab an Owner or Manager:
  - adds a category with a name of 2 to 30 characters, trimmed, unique in the salon regardless of
    case; it goes to the end of the list;
  - renames a category under the same rules;
  - moves a category up or down, and the new order is saved at once ("Order saved. Customers see
    it straight away.");
  - deletes a category that has no services, after confirming.

  A category that has services cannot be deleted: the API answers 409 `in_use` and the screen
  says "Move its N services to another category first." The last category cannot be deleted. A
  salon has at most 50 categories. Every refusal is a 400 field error or a 409, and nothing
  changes. (CAT-02)
- AC-3: An Owner or Manager creates a service with these fields and checks (the prototype's
  `seErr` messages):
  - name, 2 to 40 characters, unique in the salon regardless of case;
  - category, one of the salon's;
  - description, up to 90 characters, optional;
  - price, Rs. 100 to Rs. 500,000 in whole rupees;
  - time, 5 to 480 minutes in steps of 5;
  - buffer after, 0 to 60 minutes in steps of 5;
  - bookable online, on by default.

  Money is entered in whole units of the salon's currency, stored in minor units, scaled for other
  currencies as decision 091 does, and shown with `formatMoney`. A salon has at most 300 services.
  A broken rule is a 400 field error on that field, nothing is saved, and the sheet shows the
  message and disables **Create service** while it lasts. (CAT-01)
- AC-4: Creating a service creates its first terms version. Changing its price, time or buffer
  creates a new terms version and makes it current; earlier versions stay unchanged and readable by
  id. A save that changes only the name, description, category, photo or online setting does not
  create a terms version. A save that changes nothing is 409 `invalid_state`. A stale `version` is
  409 `conflict` and changes nothing, and of two saves of the same version sent at once exactly
  one succeeds. After a terms change the screen says "Saved. New bookings use the new prices."
  (CAT-01, CAT-04)
- AC-5: A terms version stores the service's time as ordered segments: an active segment of the
  service's time, then a buffer segment when the buffer is above zero. The API and `ServiceTerms`
  return the segments with each version. No R1 request can create a processing segment. (CAT-04,
  decision 074)
- AC-6: `ServiceTerms` returns, for the caller's salon only, a service's current terms or a terms
  version by id: service id, version id, name, price in minor units with currency, segments, and
  whether it is bookable online. Another salon's ids are "not found". A version read by id after
  three later saves, and after the service is deleted, still has its original values. (CAT-04)
- AC-7: **Duplicate** creates a copy named "<name> (copy)", or "(copy 2)" and upward when that name
  is taken, with the base name shortened so the result fits 40 characters. The copy has its own
  first terms version and starts hidden online. The sheet opens on it, and the screen says
  "Duplicated and hidden online. Review it, then show it." (CAT-01)
- AC-8: **Hide online** and **Show online** change only the online setting ("Hidden from online
  booking. Existing bookings are not affected." / "Now bookable online."). A hidden service stays
  in the workspace with a "Hidden online" badge and is absent from the public menu. (CAT-01)
- AC-9: **Delete service** asks first ("Delete “<name>”?" / "It will be removed from your menu.
  Past bookings are kept. This can’t be undone."). When `UpcomingServiceBookings` reports an
  upcoming booking for the service, the API answers 409 `in_use`, nothing changes, and the screen
  says "This service has upcoming bookings. Hide it from online booking instead." An integration
  test with an implementation that reports a booking proves the refusal. A deleted service leaves
  the workspace list and the menu, and its name can be used again. (CAT-01)
- AC-10: A service may have one photo: PNG, JPEG or WebP of up to 5 MB and at least 320 × 320
  pixels. It is checked by decoding, cropped to its centre square, re-encoded without metadata, and
  stored as a JPEG of at most 800 × 800. It is served by an opaque id on the salon's own hostname
  through the BFF. **Remove photo** removes it. A file that breaks a rule is refused with the
  reason, and the service keeps its previous photo. (CAT-01)
- AC-11: `GET /api/v1/catalogue/menu` needs no sign-in. It returns the salon's categories in the
  salon's order, each with its services that are bookable online, in name order: id, name,
  description, price with currency, the customer's time in minutes (the active segments; buffer is
  not shown to customers), the photo's URL or `null`, and the current terms version id. A category
  without such services is left out. Another salon's menu is never returned, and a suspended or
  deleted salon answers as the other public salon endpoints do. (CAT-01, CAT-02)
- AC-12: The **Services** tab lists every service grouped under its category in the salon's
  category order. Each card shows the name, an "Online" or "Hidden online" badge, the category and
  description, the price, the time, "+N min buffer" when there is one, and Edit, Hide online or
  Show online, and Duplicate. A category with no services says "No services yet." The header says
  "N services · M bookable online", and the banner says "List your services with their price and
  time. Changes apply to new bookings only." (CAT-01, CAT-02)
- AC-13: Roles:
  - every role with `catalogue.read` (all five, and a support session) sees **Services** in the
    navigation and reads both tabs;
  - only `catalogue.price.change` (Owner, Manager) sees and uses New service, Edit, Hide or Show
    online, Duplicate, Delete, New category, Rename, the order buttons and photo changes;
  - other roles get 403 `permission_denied` from every change endpoint;
  - a support session is refused every change with 403 `support_read_only`.

  Suspended and read-only salons answer as the settings endpoints do. (CAT-01, CAT-02)
- AC-14: Tenant isolation: another salon's categories, services, versions and photos are never
  readable or changeable and answer "not found". A service cannot be put in another salon's
  category. With no tenant context the new tables return no rows. (CAT-01, CAT-02)
- AC-15: Every change to a category or a service, including order, online setting, photo and
  delete, is written to the salon's audit trail with actor, time and before and after values; a
  price, time or buffer change also names the terms version created. (CAT-01, CAT-04)
- AC-16: Both tabs and both sheets provide, at every width `docs/design/ui-ux-standards.md` names:
  - loading, empty, field-error, conflict, refused and unavailable states;
  - a leave-with-unsaved-edits guard on the service sheet;
  - keyboard-only use, with accessible names for the order buttons ("Move <category> up") and the
    photo control;
  - no WCAG 2.2 AA violation in the accessibility check.
  (CAT-01, CAT-02)

## Design notes

- **Module and dependencies.** `catalogue` owns its tables, paths (`/api/v1/catalogue`, decision
  093) and settings (`salon.catalogue.*`). Add it to `module-ownership.yaml` with its description
  and OpenAPI group. It checks the branch through `branding`'s `SalonBranches`, reads the salon's
  currency through `platform`'s `SalonCurrency`, and reads the principal through `identity`'s
  `Principals`. Nothing depends on `catalogue` yet; `booking` will (F07).
- **Branch scope.** Every new table carries `tenant_id` and `branch_id` (the default branch). The
  catalogue is salon-wide in R1.
- **Room for variants (S02).** Hold the price and segments on the terms version, not on the
  service row, so S02 can add variants (each with its own price and segments) inside a terms
  version without moving data. A service without variants is one price and one set of segments.
- **Terms versions.** One row per version: immutable, with its number, author and creation time,
  readable after the service is deleted. The application role has no update or delete grant on
  it. The current version is the service's pointer to its newest version.
- **Deleting.** A deleted service is a tombstone: it leaves every list and frees its name, while
  its terms versions remain for bookings that refer to them.
- **Ordering.** Categories have a position; a move swaps two positions in one transaction. Order
  changes are checked against a version of the category list so a stale move is 409 `conflict`.
- **The guard (decision 093).** `UpcomingServiceBookings` is declared in `catalogue`'s root
  package, with an implementation in `catalogue` answering "none", replaced when `booking`
  provides its own in F07. Document the replacement in its Javadoc.
- **Photos (decision 093).** Move the decoding, limits and re-encoding from `branding` into
  `shared` with no change to branding's behaviour (its tests unchanged). `catalogue` keeps its own
  photo table and its limits under `salon.catalogue.images.*`, and serves photos as branding serves
  brand images.
- **Default categories (decision 093).** Seeded for new salons at provisioning and for existing
  salons by migration, following decision 083's default branch, and by the local seed.

## API and data changes

Routes are `catalogue`'s, on the salon's workspace host. Responses carry `version` for optimistic
concurrency.

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/catalogue/categories` | `catalogue.read` | `{listVersion, items: [{id, name, position, serviceCount, version}]}` |
| `POST /api/v1/catalogue/categories` | `catalogue.price.change` | `{name}` -> 201 the category |
| `PUT /api/v1/catalogue/categories/{id}` | `catalogue.price.change` | `{name, version}` -> 200 |
| `POST /api/v1/catalogue/categories/{id}/move` | `catalogue.price.change` | `{direction: "up" or "down", listVersion}` -> 200 the list |
| `DELETE /api/v1/catalogue/categories/{id}?version=` | `catalogue.price.change` | 204; 409 `in_use` |
| `GET /api/v1/catalogue/services` | `catalogue.read` | Every service of the salon with its current terms |
| `GET /api/v1/catalogue/services/{id}` | `catalogue.read` | The service |
| `POST /api/v1/catalogue/services` | `catalogue.price.change` | `{name, categoryId, description, priceMinor, durationMinutes, bufferMinutes, bookableOnline}` -> 201 |
| `PUT /api/v1/catalogue/services/{id}` | `catalogue.price.change` | The same fields with `version` -> 200 |
| `POST /api/v1/catalogue/services/{id}/duplicate` | `catalogue.price.change` | 201 the copy |
| `PUT /api/v1/catalogue/services/{id}/online` | `catalogue.price.change` | `{bookableOnline, version}` -> 200 |
| `DELETE /api/v1/catalogue/services/{id}?version=` | `catalogue.price.change` | 204; 409 `in_use` |
| `PUT /api/v1/catalogue/services/{id}/photo` | `catalogue.price.change` | Multipart file -> 200 the service; `DELETE` removes it |
| `GET /api/v1/catalogue/photos/{photoId}` | none (public) | The image |
| `GET /api/v1/catalogue/menu` | none (public) | `{currency, categories: [{id, name, services: [...]}]}` |

A service is `{id, name, categoryId, description, bookableOnline, photoUrl, version, terms:
{versionId, number, priceMinor, currency, segments: [{kind: "active" or "buffer", minutes}],
durationMinutes, bufferMinutes}}`. Field names may follow the API conventions where they differ;
the handoff records the final shapes.

Append migrations with the full "Adding a table" checklist (categories, services, terms versions,
photos), the default categories for existing salons and the provisioning change. Add the tables
and the path to the ownership map, and validate every new response with the contract validator.

## UI and UX

Follow the prototype (`aSvc`, `svTabs`, `catTab`, `ceOpen`, `sePaint`) and the component map:
- Tabs for Services and Categories (Products appears in S03);
- Card for each service, Badge for "Online" and "Hidden online", Item rows for categories;
- Sheet for the service and category forms, Alert Dialog to confirm a delete;
- Field with Input or Textarea, Select for the category, Field with Switch for "Bookable online";
- the file control of the component map for the photo, with its preview;
- Empty, Skeleton, Spinner, Alert and Sonner.

| Screen | Route | Behaviour |
| --- | --- | --- |
| Services | `/workspace/services` | The grouped cards of AC-12 and **+ New service** |
| Categories | `/workspace/services/categories` | Item rows in order with ↑, ↓, Rename and Delete, and **+ New category** |
| Service sheet | over either tab | Name, Price (Rs.), Time (min); under "More options": Category, Short description, Buffer after (min), Photo, Bookable online. **Create service** or **Save changes**, and **Delete service** for an existing one. Warnings from `seWarn` that apply in R1 |

The prototype sheet's pricing kinds, option groups, processing time, patch test, price list
preview and the S02 fields (Popular, Deposit) are not shown in this stage. Messages go through
next-intl, English only in R1. Every interactive element has a `data-testid`.

## Risks and test focus

- `data-integrity`: terms versions immutable and readable after later saves and after delete; no
  version for a save that changes no terms; segments stored and returned in order; money in minor
  units with no rounding; default categories for new and existing salons exactly once.
- `api` (validation): every field boundary (name 1, 2, 40, 41; price Rs. 99, 100, 500,000, 500,001;
  time 0, 5, 7, 480, 485; buffer 0, 5, 60, 65; description 90, 91), duplicate names in any case,
  the category and service limits, duplicate naming at 40 characters.
- `concurrency`: stale versions on services, categories and the list; two saves at once; two
  moves at once.
- `tenant-isolation` and `permissions`: every table and endpoint; the public menu and photo; each
  role and a support session; a category of another salon.
- `security` (uploads): wrong type renamed, oversized, too small, metadata removed; branding's image tests
  unchanged after the move to `shared`.
- `api` (contract): every response, including `null` photo and the 409 codes.
- `functional`, `responsive`, `accessibility`: both tabs, both sheets, read-only roles.

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
- `ServiceTerms` and `UpcomingServiceBookings` are documented in their Javadoc as the contracts
  F07 uses and implements.
