---
id: CR-012
title: Tenant salon login matching the supplied mockup
status: approved
requested_by: owner
requested_on: 2026-10-15
decided_on: 2026-10-15
requirements: [NFR-21]
features: [F02]
stages: [F02-S04]
integration_branch: charithm/feature/F02-salon-login-design
---

# CR-012 Tenant salon login matching the supplied mockup

## Request and decision

The owner explicitly asks to fix the login reached from a tenant hostname such as
`http://alpha.localhost:3100/`, showing the relevant salon and adhering to the supplied second
screenshot and existing HTML design. Approved directly by this instruction on 2026-10-15.
The first screenshot documents the current workspace-shell login; the second is the new login
visual authority, retained at `docs/design/references/staff-sign-in.png`.

## Impact and recommendation

- Reopen complete unreleased F02-S04 and F02 for this focused repair. Preserve all original merge
  records and intact prior backend, session, permissions and API verification. F01 routing is
  unchanged: root enters same-host workspace and unauthenticated users reach staff sign-in.
- Replace the unauthenticated workspace header/sidebar with a dedicated full-screen staff login:
  warm salon interior background, centered circular salon initial/name, translucent white card,
  Staff workspace/Welcome back headings, email or mobile and password labels, eye toggle,
  Forgot password? control, taupe sign-in button and secure-team footer. Match screenshot
  composition at 1536x1024; adapt without overflow or clipped controls at the six project widths.
- Use the API-resolved salon name and initial, never a hardcoded salon name or tenant selector.
  The current API has no logo/cover; a shared decorative interior image is the approved fallback,
  generated from the mockup without rasterized UI. Logo/cover publishing remains F04.
- Keep Poppins, translated strings, accessible labels, focus, error announcements, stable test ids,
  and existing password/MFA/session/CSRF/tenant checks. The login-specific card/field/button shape,
  neutral taupe palette and heading scale may follow this explicit mockup instead of workspace
  pills/rail tokens. Protected workspace screens keep the HTML prototype grammar.
- Forgot password? opens accessible guidance to contact the salon administrator; no password reset
  API or delivery provider exists. Do not invent a credential-reset flow or send real messages.
- QA updates obsolete login-shell visual expectations and independently verifies changed entry,
  presentation, password/MFA, recovery guidance and tenant identity through registered F02 scoped
  automation. Retain unaffected full evidence under CR-009; this is not regression or a fresh
  full-feature pass. No schema, API contract, infrastructure, payments, mobile or deployment changes.
- F03 retains its existing specification; its feature returns to backlog and S01 to outlined until
  this repair merges,
  so no F03 implementation starts against an unmerged dependency.

## Delivery

Use one scoped F02 repair PR and separate lead/developer/QA commits. Local-only runtime is HTTP.
No deployment, tag, real payment or real message is authorized. The owner merges the PR.

## Decorative asset provenance

Generated with the built-in Image Gen tool from the supplied second screenshot on 2026-10-15.
The application asset is `apps/web/public/images/staff-sign-in-interior.webp`; the original generated
1536x1024 PNG is retained outside Git. The photograph is decorative; all identity, text and form
controls are live application content. Prompt:

> Create a clean background asset for the existing salon staff login application by removing ALL
> user interface and branding from the reference mockup: remove central white card, every text
> label, form fields, button, logo and S circle. Reconstruct the cream plaster wall and natural soft
> light seamlessly in their place. Preserve the reference interior composition closely: warm ivory
> minimalist beauty salon, large thin brass arch mirror cropped at left, cream salon chair bottom
> left, light floating shelf left with small amber bottles and cream vase with olive branches, pale
> stone tiled floor along bottom, soft sunlight and subtle plant shadows across right cream wall,
> tall olive plant cropped at far right in cream stone planter. Center 60 percent must remain empty
> pale warm ivory wall for separately implemented live UI. Photorealistic upscale architecture
> photography, same muted warm beige/ivory/taupe palette, no people, no text, no UI, no logos.
> Landscape 3:2 ratio. Output only the background image.
