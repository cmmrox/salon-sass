# Mobile rules (`apps/mobile`)

Customer UI is React Native (Expo), only after every salon administration M1-web feature is
released. There is no customer website or browser fallback. Customer mode in the HTML prototype
is a deferred mobile design reference, not authorisation to start the mobile app (CR-010).

One Expo codebase produces every customer app. Read BRD section 7.4 before changing anything about
how apps are built or published; the store rules there are the reason for this design.

## Two kinds of app from one codebase

| | Container app | Dedicated app |
| --- | --- | --- |
| Published in | The operator's store accounts | The salon's own Apple and Google developer accounts |
| How a salon is chosen | A link, QR code or short code opens one salon; no directory of other salons is shown | Fixed at build time |
| Name, icon, splash, identifiers | The platform's | The salon's |

Never plan, script or suggest publishing a salon's dedicated app from the operator's accounts.
Apple rejects template apps not submitted by the content owner, and the same app under several
identifiers in one account. The distribution model itself is open decision D-01 in the BRD; if a
stage depends on it, ask.

## Configuration

- `app.config.ts` is dynamic. It reads which app is being built from an environment variable and
  takes that app's name, identifiers, icon, splash and update channel from the tenant app registry.
- No `android/` or `ios/` folder is committed. Native projects are generated at build time, so there
  is nothing per salon to maintain by hand.
- There is no per-salon code path. The only build-time difference is configuration and assets.
- One update channel per app, so an over-the-air update can never reach another salon's app.

## Branding at run time

Name, logo, cover, colour, enabled features and content come from the API when the app starts and
are cached. Publishing a brand change must not need an app release. Only what the operating system
demands before launch (icon, splash, display name) is fixed in the build.

## Structure

- Screens are thin. A screen composes components and calls a feature module; rules, API calls and
  state live in the feature module. Feature modules are named and divided like the API's modules and
  the web's `features/<module>` (CR-021), with the same rule that a feature never imports another.
- Shared with the web through `packages/*`: the generated API client per module, formatting
  (`@salon/format`), messages and design tokens. No UI component is shared: the web's shadcn/ui is
  DOM-only, and the native component library is chosen when `M2-mobile` starts. Turborepo is added to
  the workspace then, when there are two applications to build and cache.
- All API access goes through the generated client. No money, availability or policy logic on the
  device.
- Tokens are kept in the platform's secure storage, never in plain storage.
- Every screen has a loading, an empty, an error and an offline state. A payment is never submitted
  twice: the confirm action is disabled while a request is in flight and the request is idempotent.
- The app checks the minimum supported version at start and shows an update prompt when it is below
  it. A maintenance message can be shown from the server.
- Links to the salon's web address open the app when it is installed.

## Text and accessibility

- Every string comes from the translation files, shared with the web where the wording is the same.
- Support the system's larger text sizes without clipping. Touch targets are at least 44 points.
- Every control has an accessibility label. Contrast holds on the salon's brand colour.

## Tests

- Unit tests with Jest for feature modules and formatting.
- Every interactive element has a `testID`, named for what it is. QA's Maestro flows depend on them.
- When a stage touches something native (push, deep links, calendar, secure storage, permissions),
  say so in `handoff`: QA must run it on a device or emulator, and lint and unit tests prove nothing
  about it.
