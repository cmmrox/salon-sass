---
id: BUG-F02-013
title: Text on a mid-tone brand fill falls below 4.5:1 because the foreground is only ever white or ink
severity: major
status: verified
found_by: TC-F02-S04-028
breaks: AC-10
found_on: 2026-10-17
fixed_in: c618f87
fix_note: 'contrastColour keeps white or ink #111827 wherever one reaches 4.5:1 (unchanged salons) and otherwise uses #000000; 5-step RGB sweep 9,364 of 140,608 below 4.5:1 before, 0 after. Running product (alpha via local-salon.sh, restored to #D6086A): #808080 ink 4.49 -> black 5.32, #C34BB4 white 4.21 -> black 4.99 on the sign-in action, primary button and selected navigation. Evidence .local/logs/bug-f02-013-before.log, bug-f02-013-summary.log, bug-f02-013-after-runtime.log.'
developer_note: null
verified_on: 2026-10-17
approved_by: null
found_in_release: null
---

# BUG-F02-013 Text on a mid-tone brand fill falls below 4.5:1 because the foreground is only ever white or ink

## Steps

Account: a synthetic alpha MANAGER seeded through the local hook. Build: runtime `516953e` (the PR #11 application). Raised in the PR #11 review thread and confirmed by the lead; reproduced by QA.

1. Set alpha's stored colour with `infra/scripts/local-salon.sh colour alpha '#808080'` (then `'#C34BB4'`), both valid `#RRGGBB` brand colours.
2. Open `http://alpha.localhost:3100/workspace/sign-in` and read the "Sign in" action's background and text colour.
3. Sign in, open `/workspace/account` at 1280px, and read the "Change password" primary button and the selected navigation item.
4. Restore alpha's colour to `#D6086A`.

## Expected

AC-10: brand fills use the salon's colour "and its computed readable foreground", for any valid salon colour; AC-8 and the accessibility standard require at least 4.5:1 for this normal-size text (APP-10). A foreground that reaches 4.5:1 exists for every colour: with pure black as the dark candidate, the lowest best-of-two contrast over the colour space is 4.58:1.

## Actual

The computed foreground is chosen only between white and ink `#111827`, and for mid-tone brands neither reaches 4.5:1:

- `#808080`: ink 4.49:1 (white would be 3.95:1) on the sign-in action, the workspace primary button and the selected navigation.
- `#C34BB4`: white 4.21:1 (the best of the two) on the same three surfaces.

The lead's 5-step RGB sample finds 9,364 of 140,608 colours failing. Every time, on every brand-filled text surface; staff of such a salon get an unreadable primary action. Origin: the white/ink rule (`contrastColour()` in `apps/web/src/styles/brand.ts`) has been latent since F01-S03, when the brand only tinted a customer accent; CR-013 exposed it by making the brand the fill behind the sign-in action, primary buttons and selected navigation. The default `#D6086A`, beta `#1F2937` and gamma `#FDE68A` all pass, which is why TC-F02-S04-024 did not catch it.

## Evidence

TC-F02-S04-028 (`qa/web/F02/S04-theme-entry.spec.ts`), single diagnostic run on 2026-10-17 against the `516953e` runtime, alpha restored to `#D6086A` afterwards:

```
"#808080 sign-in action: rgb(17, 24, 39) on #808080 is 4.49:1",
"#808080 workspace primary button: rgb(17, 24, 39) on #808080 is 4.49:1",
"#808080 selected navigation: rgb(17, 24, 39) on #808080 is 4.49:1",
"#C34BB4 sign-in action: rgb(255, 255, 255) on #C34BB4 is 4.21:1",
"#C34BB4 workspace primary button: rgb(255, 255, 255) on #C34BB4 is 4.21:1",
"#C34BB4 selected navigation: rgb(255, 255, 255) on #C34BB4 is 4.21:1",
```

## Re-verification

Verified 2026-10-17 on `8fd1939` (fix `c618f87`, runtime meta `8fd1939`). Sealed F02-delta `qa/reports/20261017-160221-F02-delta-fbc5edc57254` from the main checkout, summary sha256 `4f3953a85d855ae8c09728c5b1f3664031f96854fc34ee0b7377478b9df8d50f`: all 14 F02 entries pass. web-theme-entry 5/5 includes TC-F02-S04-028, so `#808080` and `#C34BB4` now give text at 4.5:1 or more on the sign-in action, workspace primary button and selected navigation; TC-F02-S04-024 (alpha default, beta dark, gamma light) still passes. Alpha ended on `#D6086A`. The same check had failed on `516953e` at 4.49:1 and 4.21:1.
