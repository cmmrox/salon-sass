# F04-S01 Settings, salon profile and publishing

## Objective

An Owner or Manager opens Settings in the salon workspace and keeps the salon's business profile:
address and map position, phone, WhatsApp number, description and social links. Their edits are a
draft that only settings staff see, until one of them publishes. The API then serves the
published profile to anything that reads the salon, without an app release. Every salon has its
default branch, which later F04 stages and every later feature use for branch scope.

## Read first

- `docs/brd/requirements.yaml`: BRD-02, BRD-04 and NFR-13.
- `features/F04-salon-setup/README.md`: saving vs publishing, and the branch scope.
- `docs/architecture/multi-tenancy.md`: "Adding a table" (the branch item), "Adding an endpoint",
  "Tests you must write".
- `docs/architecture/ARCHITECTURE.md`: "Salon operations model (CR-023)".
- `docs/architecture/identity-and-access.md`: the permission table (`settings.read`,
  `settings.manage`), support-session reads, the audit trail.
- `docs/architecture/operator-console.md`: the brand bootstrap at provisioning (F03-S01), which
  now also creates the default branch.
- `docs/engineering/backend.md`: modules and provided interfaces, ownership of tables and paths,
  API conventions, persistence and migrations, the phone-number rule, tests (the contract validator
  from F04).
- `docs/engineering/web.md`: tenant routing, BFF rules, "Styling" (the shadcn/ui component map),
  "Branding", text and formats, "Tests" (the accessibility check from F04).
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon web, Settings,
  the Profile tab and its publish bar.
- `docs/decisions/DECISION_LOG.md`: decisions 042, 058, 073, 083, 084 and 085.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BRD-02 | Business profile: address and map position, phone, WhatsApp number, social links, opening hours, description. The customer's "chat on WhatsApp" link to the salon's own number is available on every plan. | In part: everything except opening hours (F04-S03). The customer's chat link is drawn by F15 from the published WhatsApp number served here |
| BRD-04 | Branding, content and enabled features are delivered from the API to the salon administration web application and later React Native customer app, so publishing a brand change does not require an app release. | In part: the published profile. The brand is F04-S02; enabled features are F18 |

NFR-13 (phone formats are configuration) applies to every phone number in this stage.

## Scope

- **API (`branding`):**
  - the branch: one default branch per salon, created for existing salons by migration and for new
    salons at provisioning, and the provided interface `SalonBranches` (the default branch, and
    whether a branch id belongs to the current salon);
  - the business profile as a draft and a published copy, with save, publish and discard;
  - the published profile added to the public `GET /api/v1/salon`;
  - the two permissions and their role assignment (in `identity`), with `settings.read` in the
    support-session read set.
- **Web:** a Settings entry in the workspace navigation; the Settings page with its tab bar; the
  Profile tab with its "What customers see" preview and the publish bar.
- **Tooling:** the accessibility check (`docs/engineering/web.md`, "Tests") and the API contract
  validator (`docs/engineering/backend.md`, "Tests"), both added by this stage.

## Out of scope

- Branding: name, tagline, logo, cover, colour and preview (F04-S02).
- Opening hours, holidays and closures (F04-S03); booking rules (F04-S04).
- The Business and Branches tabs, and a second branch (F18). No R1 screen names a branch.
- A map picker or a maps provider: the map position is typed as coordinates (decision below).
- The customer's "chat on WhatsApp" link (F15).

## Acceptance criteria

- AC-1: Every existing salon has exactly one default branch after migration, and a salon the
  operator creates has one from the transaction that creates it. `SalonBranches` returns the
  current salon's default branch, and reports a branch id of another salon, or one that does not
  exist, as not belonging to the current salon. (BRD-02)
- AC-2: An Owner or Manager sees **Settings** in the workspace navigation and reaches the Profile
  tab. The Receptionist, Professional and Accountant see no Settings entry, and opening
  `/workspace/settings` directly shows the workspace's "no access" state; their calls to the
  settings endpoints are 403 `permission_denied` and change nothing. (BRD-02)
- AC-3: An Owner or Manager saves profile changes as a draft: address, map position, phone,
  WhatsApp number, description, and Instagram, Facebook and website links. Reloading shows the
  saved draft, the publish bar says there are unpublished changes, and `GET /api/v1/salon` still
  returns the previously published profile, or `profile: null` before the first publication.
  (BRD-02, BRD-04)
- AC-4: Publishing makes the draft the published profile in one step: the next `GET /api/v1/salon`
  returns exactly the saved values, the publish bar says everything is published, and Publish is
  disabled. Publishing with nothing changed is 409 `invalid_state` and changes nothing.
  (BRD-04)
- AC-5: Discarding changes returns the draft to the published profile, or to empty before the first
  publication. The published profile does not change. (BRD-02)
- AC-6: Phone and WhatsApp numbers are checked for the configured region (`LK` by default): a
  valid Sri Lankan number in any common form (`077 123 4567`, `+94771234567`, `0112504417`) is
  stored once in E.164 and shown in the national format. An invalid number, a WhatsApp number
  that is not a mobile number, a map position outside latitude -90 to 90 or longitude -180 to
  180, a link that is not `https`, and text over its documented length are each a 400 field error
  on that field. Nothing is saved, and the screen shows the message beside the field. (BRD-02, NFR-13)
- AC-7: Two settings users edit the same draft. The second save, publish or discard sent with an
  older `version` is 409 `conflict` and changes nothing. The screen says someone else
  changed the profile and offers to reload. When two publishes of the same version are sent at
  once, exactly one succeeds. (BRD-02)
- AC-8: A salon's settings user cannot read, save, publish or discard another salon's profile:
  every settings endpoint acts only on the hostname's salon, whatever is sent. The public
  `GET /api/v1/salon` returns only the hostname's published profile. With no tenant context the
  profile and branch tables return no rows. (BRD-02)
- AC-9: A support session reads the Profile tab and the draft, and is refused saving, publishing
  and discarding with 403 `support_read_only`. A suspended salon's settings endpoints answer 403
  `salon_suspended`, and a read-only salon's writes 403 `salon_read_only`, like its other endpoints. (BRD-02)
- AC-10: Saving, publishing and discarding are each recorded in the salon's audit trail, with the
  actor, the time, the action and the names of the changed fields. No field value appears in the
  audit, logs or error responses. (BRD-02)
- AC-11: Leaving the Profile tab with unsaved edits asks for confirmation. The Profile tab and the
  publish bar provide loading, saved, field-error, conflict, unavailable and published states at
  every width `docs/design/ui-ux-standards.md` names. They can be used with the keyboard alone
  and have accessible labels, and the accessibility check reports no WCAG 2.2 AA violation.
  "What customers see" updates as the user types. (BRD-02)

## Design notes

- **Module:** all of it is `branding`'s, except the two permissions, which `identity` assigns to
  roles (decision 085). The default branch is created by `branding`'s existing provisioning facade
  in the salon-creation transaction. Extend the bootstrap function or add a second one; the
  platform connection must still be unable to touch any other salon's rows.
- **Branch:** `branding_branch` holds the branch identity (id, name, default marker). The default
  branch is named after the salon's display name and is not shown in R1. At most one branch per
  salon is the default, which a partial unique index enforces.
- **Draft and published:** each salon has one draft and at most one published copy of the
  salon-wide profile (description, links), and of each branch's contact details (address, map
  position, phone, WhatsApp). Publishing copies the draft to the published copy in one
  transaction. Discarding copies the published copy back, or empties the draft. The draft carries
  a `version` for optimistic concurrency, and the published copy records when and by whom it was
  published.
- **Lengths:** address 200 characters, description 500, each link 200; trimmed, and empty means
  "not set". Every field is optional, so a salon can publish a partial profile.
- **Map position:** latitude and longitude as decimals, typed as one value
  (`6.9061, 79.8611`) and checked for range. The screen offers "Check on map", a link that opens
  the position in Google Maps in a new tab. It needs no key and sends nothing from the server.
- **Phone numbers:** decision 084. The region comes from `salon.branding.phone-region`.
- **Permissions:** `settings.read` for reading the draft, `settings.manage` for save, publish and
  discard, both checked in the service (decision 085).
- **Errors:** field errors use the existing problem-details shape with field names matching the
  request (400 `bad_request` with `errors`). Use the existing codes in `shared.web.ErrorCode`:
  `conflict` for a stale `version`, `invalid_state` for nothing to publish, `permission_denied`,
  `support_read_only`, `salon_read_only` and `salon_suspended`, each documented on every operation
  it can come from. No new code is needed.

## API and data changes

Settings routes are on the salon's workspace host only, under `branding`'s `/api/v1/salon` prefix.

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/salon/profile/draft` | `settings.read` | `{version, profile, hasUnpublishedChanges, publishedAt, publishedBy}` |
| `PUT /api/v1/salon/profile/draft` | `settings.manage` | `{version, profile}` -> 200 the draft as above; 400 field errors; 409 `conflict` |
| `POST /api/v1/salon/profile/publication` | `settings.manage` | `{version}` -> 200 the draft as above; 409 `conflict`, or `invalid_state` when nothing changed |
| `DELETE /api/v1/salon/profile/draft` | `settings.manage` | `{version}` -> 200 the draft as above; 409 `conflict` |
| `GET /api/v1/salon` (public, existing) | none | Gains `profile`: null before the first publication |

`profile` in both places has one shape:

```json
{
  "description": "A friendly neighbourhood salon for hair, beauty and bridal.",
  "links": { "instagram": "https://instagram.com/yoursalon", "facebook": null, "website": null },
  "branches": [
    {
      "id": "…",
      "isDefault": true,
      "address": "No. 24, Flower Road, Colombo 07",
      "location": { "latitude": 6.9061, "longitude": 79.8611 },
      "phone": "+94112504417",
      "whatsapp": "+94774123300"
    }
  ]
}
```

In R1 `branches` always holds exactly the default branch. Clients must not assume it holds one.
The draft request sends the same shape. A branch id in it that is not the salon's is a 400 field
error, the same as one that does not exist.

Append migrations for `branding_branch`, the profile tables and the backfill of a default branch
(and an empty draft) for every existing salon, each with the full "Adding a table" checklist. Add
them to `branding`'s entry in the ownership map. Grant the application role only what this stage
uses. Regenerate the contract and the generated client.

## UI and UX

Follow the prototype: Salon web, **Settings**, the **Profile** tab. Look up every component in
the map in `docs/engineering/web.md`, "Styling":
- Tabs for the tab bar;
- Field with Input and Textarea for the inputs, with "Check on map" as a Button beside the map
  position, as the prototype draws it;
- Card for the form, the preview and the publish bar;
- Button and Spinner;
- Alert for conflict and unavailable;
- Alert Dialog for leaving with unsaved edits;
- Sonner for "Draft saved" and "Published".

| Screen | Route | Behaviour |
| --- | --- | --- |
| Settings | `/workspace/settings` | Opens the first tab the user may see; the tab bar shows only the tabs that have been delivered, which in this stage is Profile |
| Profile | `/workspace/settings/profile` | Contact (address, map position with "Check on map", phone, WhatsApp), About (description with a character count), Links. "What customers see" beside it on wide screens, below it on narrow ones. **Save draft** saves the form |
| Publish bar | Below the Profile tab | "Unpublished changes" with "Only staff who manage settings can see them until you publish", or "Everything is published". **Publish to everyone** saves any unsaved edits, then publishes. **Discard changes** asks for confirmation |

Phone numbers are typed and shown in the national format. Messages go through next-intl, in English
only in R1 (decision 072). Every interactive element has a `data-testid`, named as
`docs/design/ui-ux-standards.md` says.

## Risks and test focus

- `tenant-isolation`: the new tables and endpoints, and the public profile bound to the hostname.
  Two-tenant tests from inside and outside.
- `data-integrity`: the backfill (one default branch per salon, none twice), and the provisioning
  path. The published copy must equal the draft at publication. Discard must not touch the
  published copy.
- `concurrency`: stale `version` on every write; two publishes at once.
- `permissions`: five roles, a support session and a suspended salon on every endpoint.
- `contract`: `profile: null` before publication, nullable links and location, every documented
  status. The new validator proves the real responses match the schema.
- `validation` and `localisation`: phone forms and the mobile rule for WhatsApp, coordinate ranges,
  `https` links, lengths, national display.
- `functional`, `responsive`, `accessibility`: the Settings page, the Profile tab, the preview, the
  publish bar and the unsaved-edits guard.

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

- The developer has checked every acceptance criterion on the running product, with two
  synthetic salons and each role, and recorded it in the handoff.
- The verification commands are green.
- `pnpm --filter @salon/web test:a11y` exists and checks every screen this stage adds. An API
  integration test validates the new endpoints' real responses against the committed schema.
- The handoff names any new setting (`salon.branding.phone-region`) and any local hook, so the lead
  can update the environment documents.

## Corrections at close

Two lines of this spec were the lead's errors and were corrected when the stage closed. Neither
changes a requirement or an acceptance criterion:
- Messages: the staff workspace is English in R1 (decision 072), not English, Sinhala and Tamil.
- The map position uses a plain Input with a Button beside it, as the prototype draws it. An Input
  Group crushed the value at 360px.
