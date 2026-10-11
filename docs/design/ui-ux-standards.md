# UI and UX standards

Consistency across stages is a delivery requirement, not a polish pass. A customer should not be
able to tell which screens were built in which stage.

## Sources of truth, in order

1. The stage's `spec.md`.
2. The prototype, `docs/design/prototype/index.html`: screens, flow, wording and states.
   Open it in a browser and use both modes (Customer and Salon). It is a single-file demonstration:
   copy its behaviour and look, never its code.
3. The business rules read from it, BRD section 4 (BR-01 to BR-22). Where the BRD's requirements
   change a prototype rule (for example, policies become per-salon settings), the requirement wins.
4. The existing components and tokens in the codebase.

When these disagree, stop and ask the lead. Do not pick one silently.

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
