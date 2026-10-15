# UI and UX standards

Consistency across stages is a delivery requirement, not a polish pass. A customer should not be
able to tell which screens were built in which stage.

## Design authority

The owner's supplied `docs/design/prototype/index.html` is the visual and interaction reference
with an explicit platform mapping: Salon mode is the owner/admin/staff web workspace; Customer
mode is a deferred React Native mobile design, never customer web. M1-web must finish and be
released before mobile implementation (CR-010). Follow its final CSS cascade, including the later mobile polish
and responsive workspace overrides. Match composition, typography, colours, spacing, control
shapes and navigation. A stage spec limits delivered capabilities; it does not authorise a new
visual direction. Approved business/security requirements govern behaviour where they change a
prototype rule. A visual departure requires the owner's explicit instruction recorded by the lead.

For a screen absent from the prototype, extend its visual grammar through shared components.
Existing components are reusable only when they conform to the reference. The supplied HTML is
preserved except for owner-approved revisions (CR-010 labels platforms and defaults to Salon); do not copy demonstration data or inline handlers
into production. Show only available routes. Future catalogue, booking, branding and native mobile
capabilities stay in their roadmap features; Customer mode remains their mobile design reference.
If a spec contradicts this authority, ask the lead to correct it through a change request.

## Staff sign-in exception (CR-012)

The owner's `docs/design/references/staff-sign-in.png` governs tenant staff login only: full-screen
warm interior photo, centered API-resolved salon initial/name, translucent white card, welcome
headings, labelled fields, visibility toggle, administrator recovery guidance, taupe action and
secure-team footer. Match its composition at 1536x1024 and adapt across the width matrix. Use
Poppins and accessible tokenized colors; login radii/heading scale replace workspace rail/pills.
Published logo/cover support stays in F04; the interior is a shared decorative fallback. Protected
screens continue to follow the HTML prototype below.

## Reference contract

| Element | Final prototype reference |
| --- | --- |
| Canvas / secondary / borders | `#F3F0EB` / `#EAE5DC` / `#DDD6CA`; white cards; ink `#111827`, muted `#6B7280` |
| Typography | Poppins 400/500/600; 15px body; 22px customer title; 26px desktop workspace title |
| Cards / fields / primary actions | 24px cards, 20px fields, pill actions; fields at least 48px and primary actions 50px |
| Customer (later React Native only) | Centred 430px canvas, compact sticky header and anchored icon navigation |
| Salon | Full width; below 900px main area up to 760px and bottom navigation; desktop sticky white header, sidebar and main area up to 1280px |
| Sidebar | 200px at 900–1199px; 236px at 1200px and above |
| Branding | Customer actions and identity accents use the published salon colour and readable computed foreground; workspace stays neutral |

The customer header's name is neutral text on the warm canvas. Customer and workspace header
names use the reference's single-line ellipsis, retaining the complete DOM/accessible name and
full-name tooltip; this deliberate header truncation is distinct from clipped controls/content. Until logo/cover data is available,
use a circular initial fallback; a large solid brand banner does not match the reference. Account,
security and privacy screens inherit its cards, fields and pills; staff/audit lists inherit its
responsive rows/table grammar. Confirmation overlays follow its bottom-sheet phone layout and
right-side desktop drawer with keyboard focus containment, cancel/Escape and focus return. Foundation status/error screens and proxy plain HTML inherit the
same warm typography/cards/controls. Muted small text uses white surfaces (4.83:1 contrast);
use existing ink for small text on the warm canvas (muted would be 4.25:1 there). The reference
positive text `#0F7B55` on `#DDF1E6` is only 4.4669:1; normal-size success text uses existing ink
on that green surface. Keep the reference semantic palette and background; do not lower the AA
threshold. Accessibility requirements govern this pairing (BUG-F01-003).

## Visual handoff gate

Map each implemented screen to its reference screen, or name the reference components it extends.
Compare the implemented platform only: salon phone/tablet/desktop during M1, customer native
screens during M2. Then verify the width matrix
and states. Check loaded font, canvas width, colours, radii, navigation position, spacing, long names,
keyboard focus and brand contrast. Keep evidence with developer handoff and QA review. Functional,
responsive and accessibility passes alone cannot establish design similarity. If a browser cannot
preview the reference, report the limitation and compare its source-defined contract with rendered
product evidence; never claim a side-by-side preview occurred.

## Build order for a screen

1. Find the prototype screen and list its states: loading, empty, filled, error, disabled.
2. Search the codebase for components that already do part of it.
3. Build from tokens and existing components. Add a new component only when nothing fits, and add
   it to the shared set, not to the screen.
4. Check it at every width in the matrix and in every state before handing over.

## Tokens and branding

- Colours, spacing, radii, type sizes and shadows are tokens. No raw hex value and no magic number
  in a component.
- Two kinds of colour: the platform's neutral palette, and the salon's brand colour, which arrives
  at run time. Anything drawn on the brand colour uses the computed contrast colour.
- Test every customer screen with at least two salons' brands: a dark brand colour and a light one.
  A screen that only works on the default pink is broken.
- Images uploaded by a salon are resized and cropped by the system. A layout must survive a very
  wide logo, a missing cover and a long salon name.

## States every screen has

| State | Rule |
| --- | --- |
| Loading | A skeleton shaped like the content. No blank screen, no spinner covering a page that could show something |
| Empty | Says what belongs here and offers the next action |
| Error | Says what happened in plain words and what the person can do. Never a code or a stack trace |
| Slow or offline | Says so, keeps what is already on screen, and retries safely |
| Success | Confirms what was done, with the detail a person would want to check (date, time, amount) |

## Booking flow

- Five steps as prototyped: services, professional, time, details, payment. A progress indicator and
  a summary of price and duration stay visible throughout.
- The hold countdown is always visible once a time is chosen. When it runs out, say so and offer
  the nearest alternatives; never fail silently at the payment step.
- Show the cancellation policy and the exact amount to be paid now before the customer confirms.
- "That time was just taken" is a normal outcome. Keep the customer's choices and offer other times.

## Formats for the launch market

| Thing | Format |
| --- | --- |
| Currency | `Rs. 2,500` for whole rupees; show cents only when they are not zero |
| Phone | Sri Lankan mobile numbers: `07x xxx xxxx` or `+94 7x xxx xxxx`. Validate, then store in international form |
| Dates and times | In the salon's time zone, always. Say the day name for anything within a week |
| Languages | English first. Sinhala and Tamil follow, so leave room: translated text is often longer |

These come from configuration and the formatting module, never from a literal in a screen.

## Width matrix

| Width | Stands for |
| --- | --- |
| 360 | Small Android phone, the most common device in the launch market |
| 390 | Current iPhone |
| 768 | Tablet at the front desk |
| 1024 | Small laptop |
| 1280, 1440 | Desktop |

No horizontal scrolling at any width. The workspace calendar must be usable at 768.

## Accessibility

The reference is WCAG 2.2 level AA. In practice: real buttons and links, a label on every input,
errors tied to their fields, visible focus, full keyboard use on the web, touch targets of at least
44 points, contrast of 4.5 to 1 for text, and nothing conveyed by colour alone (a booking's state
has a word or an icon, not only a colour).

## Test ids

Every element a person can press, type into or read a result from carries a test id: `data-testid`
on the web, `testID` in the customer app.

- Name it for what it is, in kebab-case, as `<area>-<thing>` or `<area>-<thing>-<action>`:
  `booking-confirm`, `service-list`, `status-api-version`, `hold-countdown`.
- A repeated item carries the same id on each row and an identifying attribute beside it
  (`data-id`), not an index baked into the name.
- Never name one for how it looks (`pink-button-2`) or where it sits (`header-3`).
- A test id is part of the contract with QA. Renaming one is a change to announce in `handoff.notes`.

## Wording

Plain, short, in the customer's terms. "Your booking is confirmed", not "Transaction successful".
Use the prototype's wording where it exists. One name for one thing everywhere: "professional",
"booking", "deposit".

## Red flags

- A new component that looks like an existing one.
- A colour, size or string typed straight into a screen.
- A screen checked only at desktop width, only with the default brand, or only with data in it.
- An interactive element with no test id.
