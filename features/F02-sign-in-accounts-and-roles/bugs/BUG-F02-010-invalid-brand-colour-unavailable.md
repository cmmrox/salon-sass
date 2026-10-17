---
id: BUG-F02-010
title: A salon brand without a valid colour makes sign-in "Service unavailable" instead of using the default accent
severity: minor
status: verified
found_by: TC-F02-S04-025
breaks: AC-10
found_on: 2026-10-17
fixed_in: 3b8df96
fix_note: 'findSalon keeps a salon whose brandColour is missing or not #RRGGBB, with the #D6086A default (slug, name and time zone stay strict); stand-in check on :3101 rendered sign-in 200 in the default accent for not-a-colour, the injection value and a missing colour. Evidence .local/logs/bug-f02-010-after.log.'
developer_note: null
verified_on: 2026-10-17
approved_by: null
found_in_release: null
---

# BUG-F02-010 A salon brand without a valid colour makes sign-in "Service unavailable" instead of using the default accent

## Steps

Account: none (signed out). Data: synthetic alpha. Tested source `be7e550` (application identical to `756fdef`); local web on :3100.

1. Stop the API and put a stand-in on its port that answers `GET /api/v1/salon` for `alpha.localhost` with alpha's published brand (slug, name, time zone) and `GET /api/v1/meta`.
2. Serve `brandColour: "#0E7490"` and open `http://alpha.localhost:3100/workspace/sign-in` (control).
3. Serve `brandColour: "not-a-colour"`, then `"#fff;}body{display:none"`, then omit `brandColour`; open the same page each time.

## Expected

AC-10 (CR-013): "A salon without a valid colour gets `#D6086A`." The sign-in renders with alpha's name and the default accent, with a readable foreground.

## Actual

The control renders the sign-in in `#0E7490` (200). Each invalid or missing colour returns 503 with the status page "Service unavailable — We could not reach the service" under a generic "Salon" header, and no sign-in. The salon's staff could not sign in at all. Every time (3 of 3 values, in the recorded run and in two development runs).

Reaching this needs the API to send an invalid colour: today the database CHECK prevents one, so the effect is latent until another source (F04 brand settings, a contract change, a cache) produces one. The developer's handoff says "strict web resolution keeps an invalid colour from reaching a screen". It does, but by refusing the whole salon instead of falling back as the criterion states.

## Evidence

- Recorded run `qa/reports/20261017-063102-F02-delta-ff3abe97e455`, group `web-theme-entry`, TC-F02-S04-025 failure row "malformed": `qa/reports/20261017-063102-F02-delta-ff3abe97e455/005-F02-S04-web-theme-entry.log` and screenshot under `qa/reports/20261017-063102-F02-delta-ff3abe97e455/playwright/`.
- Direct probe (same stand-in, curl of the sign-in): `#0E7490` 200 with sign-in; `not-a-colour`, injection value and missing colour 503 "could not reach the service"; `#d6086a` (lower case) 200.

## Re-verification

Verified 2026-10-17 on the handoff: running API/web from `f62146f` (application identical to the fix `3b8df96`), QA revision `b7f5c3d`. Recorded F02-delta `qa/reports/20261017-092347-F02-delta-b0a937960444`, group `web-theme-entry` 4/4: TC-F02-S04-025's stand-in rows `not-a-colour`, the injection value and a missing colour each render sign-in with alpha's name in `#D6086A` and a readable foreground, after the valid-colour control rendered `#0E7490`; its colour-hook half and TC-F02-S04-024 also pass. The same rows had failed in `qa/reports/20261017-063102-F02-delta-ff3abe97e455`.
