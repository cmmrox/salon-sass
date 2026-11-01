# F04-S02 Branding draft, live preview and publish

## Objective

An Owner or Manager sets the salon's brand in Settings, on the Branding tab: name, tagline, logo,
cover photo and brand colour. A live preview shows how customers will see it. Like the profile,
the brand is a draft until published. Publishing changes the brand in every salon screen at the
next page load, and the API serves it to every client, without an app release.

## Read first

- `docs/brd/requirements.yaml`: BRD-01 and BRD-04.
- `features/F04-salon-setup/README.md`: saving vs publishing, and the decisions recorded at
  F04-S01's close.
- `features/F04-salon-setup/stages/S01-profile-and-publishing/spec.md` and its `stage.yaml`
  handoff: the draft, version, publish and discard pattern, the settings permissions and the
  Settings page this stage extends. Reuse them; do not build a second pattern.
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Caches, files,
  search and logs", "Tests you must write".
- `docs/architecture/tech-stack.md`, "Files", and `docs/decisions/DECISION_LOG.md`: decision 086
  (brand images in PostgreSQL), and decisions 042, 083 and 085.
- `docs/engineering/backend.md`: modules, API conventions, persistence and migrations, jobs,
  security, tests (the contract validator).
- `docs/engineering/web.md`: tenant routing (nothing on a salon's hostname is cached), BFF rules,
  "Styling" (the component map, including the three rows added for this stage), "Branding" (the
  brand colour rule and the text colour on it), "Tests" (the accessibility check).
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon web, Settings, the
  **Branding** tab (`setBrand`) and its preview.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BRD-01 | Brand settings: salon name, tagline, logo, cover photo and brand colour, with a live preview. Changes remain a draft until published. Text colour on the brand colour is chosen automatically for contrast. | Full |
| BRD-04 | Branding, content and enabled features are delivered from the API to the salon administration web application and later React Native customer app, so publishing a brand change does not require an app release. | In part: the published brand. The profile was F04-S01; enabled features are F18 |

## Scope

- **API (`branding`):**
  - the brand draft (name, tagline, logo, cover, colour) with save, publish and discard, on the
    F04-S01 pattern;
  - "Reset to default branding" as a draft change;
  - brand image upload, checking and re-encoding, storage, and serving (decision 086);
  - a cleanup job for images that neither the draft nor the published brand uses;
  - the published tagline, logo and cover added to `GET /api/v1/salon`.
- **Web:**
  - the Branding tab with its preview;
  - an image route on the salon's hostname;
  - the published logo shown wherever the workspace already shows the salon's initial (the
    workspace header, the sign-in page).

## Out of scope

- Image cropping or editing in the browser: the logo is cropped to the centre square on the
  server, and the preview shows how the cover is cut.
- Custom domains and the app registry (BRD-03, BRD-05 to BRD-08).
- The business type line under the name in the prototype's preview: there is no business type
  until F18. The preview shows the tagline there.
- Object storage (decision 086).

## Acceptance criteria

- AC-1: An Owner or Manager opens **Settings → Branding**. The tab bar shows Profile and Branding.
  The form shows the current draft, and the preview shows its cover, its logo as a circle, the
  name, the tagline, and a "Book an appointment" button in the draft colour. Receptionist,
  Professional and Accountant cannot reach the tab, and their calls are 403
  `permission_denied`. (BRD-01)
- AC-2: Changing the name, tagline or colour updates the preview as the user types or chooses,
  before anything is saved. A colour is chosen from the prototype's six swatches or typed as
  `#RRGGBB`. The button text on the preview is white, ink or black by the contrast rule in
  `docs/engineering/web.md`, "Branding", and reaches at least 4.5:1 for every colour, including
  mid-tones such as `#808080`. (BRD-01)
- AC-3: Saving keeps the change as a draft. Reloading shows it, the publish bar says there are
  unpublished changes, and the workspace accent, `GET /api/v1/salon` and the sign-in page still
  show the published brand. Publishing makes the draft the published brand in one step. At the
  next page load every salon screen uses the new colour, name and logo, and `GET /api/v1/salon`
  returns them. Discard returns the draft to the published brand. Publishing with nothing changed
  is 409 `invalid_state`. (BRD-01, BRD-04)
- AC-4: Logo upload accepts a PNG, JPEG or WebP image of up to 5 MB and at least 128 × 128
  pixels. The logo is cropped to its centre square and stored as a PNG of at most 512 × 512.
  Cover upload accepts the same types and size, at least 800 × 300 pixels, and is stored as a JPEG
  at most 1600 pixels wide. Both are re-encoded, so metadata such as camera and GPS data is not
  kept. Each of the following is a 400 field error on that image and stores nothing:
  - a file whose content is not one of the three types, whatever its name or declared type;
  - a larger file;
  - a smaller image;
  - an image that cannot be decoded.
  (BRD-01)
- AC-5: "Use default logo" and "Use default cover" remove that image from the draft. "Reset to
  default branding" sets the draft colour to `#D6086A`, removes the logo, the cover and the
  tagline, and keeps the name. Each change is a draft until published. With no logo, every screen
  shows the salon's initial as now; with no cover, the preview shows the prototype's neutral
  cover. (BRD-01)
- AC-6: The name is required, 1 to 60 characters after trimming. The tagline is optional, up to 80
  characters. The colour must be `#RRGGBB` and is stored in upper case. Breaking any of these is a
  400 field error on that field, and nothing is saved. (BRD-01)
- AC-7: A published image is served on its salon's hostname to anyone. An image used only by the
  draft is served only to that salon's settings users. Neither is served on another salon's
  hostname: the answer is 404, exactly as for an id that does not exist. Every image response
  carries the salon hostname's no-store caching and `X-Content-Type-Options: nosniff`. (BRD-01)
- AC-8: The two-tenant guarantees hold on every new table and endpoint:
  - a salon's settings user cannot read, save, publish, discard or upload to another salon's
    brand;
  - another salon's image id is refused in a draft as a 400 field error, the same as an id that
    does not exist;
  - with no tenant context the new tables return no rows.
  (BRD-01)
- AC-9: A stale `version` on save, publish, discard or reset is 409 `conflict` and changes nothing.
  When two publishes of the same version are sent at once, exactly one succeeds. A support
  session reads the Branding tab and is refused every change and upload with 403
  `support_read_only`. A suspended salon answers 403 `salon_suspended`, and a read-only salon's
  writes answer 403 `salon_read_only`. (BRD-01)
- AC-10: An image that neither the draft nor the published brand has used for 24 hours is deleted
  by the cleanup job. A published image, or one the draft uses, is never deleted, and the job
  touches no other salon's images. This is proved with the test clock. (BRD-01)
- AC-11: Saving, publishing, discarding, resetting and uploading are each recorded in the salon's
  audit trail, with the actor, time, action and the names of the changed fields. No image bytes,
  file names or field values appear in the audit, logs or error responses. (BRD-01)
- AC-12: The Branding tab, its upload states (choosing, uploading, refused) and the publish bar
  provide loading, saved, field-error, conflict, unavailable and published states at every width
  `docs/design/ui-ux-standards.md` names. They can be used with the keyboard alone, the swatches
  have accessible names, and the accessibility check reports no WCAG 2.2 AA violation with a dark
  and a light draft colour. (BRD-01)

## Design notes

- **Pattern:** the F04-S01 draft pattern, unchanged. The brand and the profile have separate
  drafts, versions and publish bars, so publishing one never publishes the other. In the
  prototype one Publish covers both; the separate bars are this stage's deliberate difference,
  shown in the same place and with the same words.
- **Published brand:** `salon_brand` stays the published brand that everything already reads. It
  gains nullable tagline, logo and cover columns, added without breaking the running version.
  The draft lives in a new `branding` table with the `version`.
- **Images (decision 086):** a `branding` tenant table holds each image with:
  - an opaque id;
  - its kind (logo or cover);
  - its bytes, media type, width and height;
  - its creation time.
  Uploading creates an image row and does not change the draft; the client then saves the draft
  with the image id. Check the content by decoding it, never by its name or declared type, and
  re-encode it before storing. Choose the library with WebP read support, and note the choice in
  the handoff. Limit the decoded pixel count before decoding, so that a decompression bomb is
  refused rather than exhausting memory.
- **Serving:** the API serves an image by id. On the web, a route on the salon's hostname
  (`/brand-images/<id>`) passes it through the BFF. Whether an image is public is decided by the
  API on every request, from the published brand.
- **Cleanup:** a scheduled job, per salon, on the existing job scheduler. The 24-hour grace period
  lets a user upload, preview and save without losing the image.
- **Colour and text colour:** the stored colour is any `#RRGGBB`. The text colour on it is not
  stored; every client computes it with the documented rule. The web already has that rule
  (`src/styles/brand.ts`); the preview uses the same function, never a copy.
- **Preview scope:** the draft colour is applied only inside the preview card. The rest of the
  workspace keeps the published brand until publication. In the prototype the whole editor changes
  colour; this is a deliberate difference, so that an unpublished colour never looks published.
- **Permissions:** `settings.read` to read the draft and its images, `settings.manage` for every
  change and upload (decision 085).

## API and data changes

All routes are `branding`'s, under `/api/v1/salon`, on the salon's workspace host.

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/salon/brand/draft` | `settings.read` | `{version, brand, hasUnpublishedChanges, publishedAt, publishedBy}` |
| `PUT /api/v1/salon/brand/draft` | `settings.manage` | `{version, brand}` -> 200 the draft as above; 400 field errors; 409 `conflict` |
| `POST /api/v1/salon/brand/draft/reset` | `settings.manage` | `{version}` -> 200 the draft with the defaults; 409 `conflict` |
| `POST /api/v1/salon/brand/publication` | `settings.manage` | `{version}` -> 200 the draft; 409 `conflict`, or `invalid_state` when nothing changed |
| `DELETE /api/v1/salon/brand/draft` | `settings.manage` | `{version}` -> 200 the draft; 409 `conflict` |
| `POST /api/v1/salon/brand/images` | `settings.manage` | `multipart/form-data`, `kind` (`logo`, `cover`) and `file` -> 201 `{id, kind, width, height}`; 400 field error on `file`; 413 beyond the request limit |
| `GET /api/v1/salon/brand/images/{id}` | Public when published, else `settings.read` | The image bytes with its media type; 404 otherwise |
| `GET /api/v1/salon` (existing) | none | Gains `tagline` (nullable), `logo` and `cover`, each `{id, width, height}` or null |

`brand` has one shape in the draft request and response: `{name, tagline, colour, logo, cover}`,
where `logo` and `cover` are `{id, width, height}` or null in a response, and an image id or null
in a request. The existing `name` and `brandColour` of `GET /api/v1/salon` keep their names.

Append migrations for the draft and image tables with the full "Adding a table" checklist, and
add them to the ownership map. Add the new columns of `salon_brand`, and back-fill a draft equal
to the published brand for every existing salon. A new salon gets its draft from the provisioning
bootstrap. Grant the application role only what this stage uses: it now updates `salon_brand`,
which was read-only until F04. Regenerate the contract and the generated client, and validate the
new responses, including the image endpoint's statuses, with the contract validator.

## UI and UX

Follow the prototype: Salon web, **Settings**, **Branding** (`setBrand`). Use the component map in
`docs/engineering/web.md`, "Styling":

- **Tabs and the form:** Tabs (Profile, Branding, in the prototype's order), Card, and Field with
  Input for the name, tagline and `#RRGGBB` colour.
- **Colour and images:** Toggle Group for the six swatches, and the file-choice row for logo and
  cover.
- **Avatar:** for the logo circle and the initial fallback.
- **Actions and messages:** Button, Spinner, Alert, Alert Dialog for Discard and Reset, and Sonner
  for passing notices.

| Screen | Route | Behaviour |
| --- | --- | --- |
| Branding | `/workspace/settings/branding` | Preview card (cover, logo circle, name, tagline, "Book an appointment" in the draft colour); Salon details (name, tagline); Images (logo with "Use default logo", cover with "Use default cover", each showing the chosen image, an uploading state and its error); Brand colour (swatches, `#RRGGBB` field); **Reset to default branding**; **Save draft** |
| Publish bar | Below the Branding tab | The F04-S01 publish bar, for the brand |
| Logo elsewhere | Workspace header and sign-in | The published logo in the existing Avatar, the initial when there is none |

The leave-with-unsaved-edits guard covers the Branding tab, including an uploaded image that has
not been saved into the draft. Messages go through next-intl, in English only in R1 (decision
072). Every interactive element has a `data-testid`.

## Risks and test focus

- `security`: uploads checked by content, decompression bombs, re-encoding (no metadata kept),
  `nosniff`, draft images not public.
- `tenant-isolation`: images served only on their own salon's hostname; another salon's image id
  refused in a draft; the cleanup job bound to one salon.
- `data-integrity`: the publish copies exactly the draft; the back-fill; the cleanup never deletes
  an image in use; `salon_brand` stays valid for the running version during migration.
- `concurrency`: stale versions; two publishes at once.
- `time`: the 24-hour grace period with the test clock.
- `permissions`: five roles, a support session, suspended and read-only salons.
- `contract`: nullable tagline, logo and cover; multipart and binary responses; every documented
  status.
- `functional`, `responsive`, `accessibility`: preview fidelity, contrast of the preview button for
  any colour, the swatches, the upload states, keyboard use.

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
  synthetic salons, each role, a support session and real image files (including a renamed
  non-image and an oversized one), and recorded it in the handoff.
- The verification commands are green.
- The handoff names the image library chosen, the upload and pixel limits as configured, any new
  setting and any local hook, so the lead can update the documents.

## Corrections at close

Recorded by the lead when the stage closed. No requirement changed:
- **Security limits added during the build.** A cover is kept at most 1600 high as well as 1600
  wide, so a cover taller than it is wide is refused as a 400 field error. Decoding is held to a
  16M-pixel budget, and two images at most are decoded at once (503 beyond).
- **Upload size errors.** A file of 5 to 10 MB is a 400 field error on `file`. Only a request
  over 10 MB is 413.
- **Cleanup timing.** The 24 hours run from when an image stopped being used, or from its upload
  if it was never used.
- **Logo placement.** The workspace header showed no initial before this stage. It now shows the
  published logo before the name when there is one, and nothing extra otherwise. On sign-in the
  logo replaces the initial.
