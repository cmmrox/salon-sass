---
id: BUG-F04-001
title: "A logo, cover or photo uploaded over a slow connection is answered 503 because the 3-second limit is counted from the first byte of the request, not from the end of the upload"
severity: major
status: fixed
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "BRD-01 (logo and cover photo); also STF-01 (member photo) and CAT-01 (service image), which use the same path"
found_on: 2026-11-10
fixed_in: 199d2bd5499fd21265b301b4de0e78975eacd6a8
fix_note: "The request log starts the request's 3-second API budget again when the request's body has arrived (Next.js buffers the whole body before a route handler runs), and the workspace BFF reads a brand image, service photo or team photo form before its session check and gives the rest of that request its own stated limit, UPLOAD_API_TIMEOUT_MS (10 s), from the end of the read."
developer_note: "Reproduced by unit tests only, as briefed (no local instance started): upload-deadline.test.ts (a team photo whose body arrives over 750 ms against a 300 ms budget reached the upload call with its signal already aborted) and request-log.test.ts (a body sent over 1.2 s left 1.8 s of the 3 s budget). A real slow-uplink check needs the running product (QA instance, throttled browser). Not changed: the operator console's holiday CSV import and the export download (platform-web.ts) still use the request's budget; the export is the 'long read' web.md says needs its own limit."
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F04-001 A logo, cover or photo uploaded over a slow connection is answered 503 because the 3-second limit is counted from the first byte of the request, not from the end of the upload

Filed by the lead from `docs/reviews/2026-11-10-foundation-review/README.md` (decision 113). The developer reproduces it first, as `bug-fix` requires; if it
does not reproduce, say so in `developer_note` and leave it for QA.

## Steps

Account: an Owner. Browser throttled to about 1.5 Mbps upload (or a 3 MB image on a phone connection).

1. Open Settings, Branding, and upload a 3 MB logo or cover. Repeat for a team member's photo and a service's photo.

## Expected

The upload succeeds: the time limit for the API call starts once the browser has finished sending the file, and an upload has its own stated limit (docs/engineering/web.md, Structure).

## Actual

By reading: the BFF sets its deadline when the request headers arrive, then reads up to 10 MB of body, then calls the API with the same deadline. A body that takes longer than 3 seconds to arrive is aborted before the API is called and the screen says the service is unavailable. It always works on localhost.

## Evidence

- `apps/web/src/lib/observability/request-log.ts:39`; `apps/web/src/lib/api-core/client.ts:54-79`.
- `apps/web/src/features/identity/server/identity-web.ts:409,666-685`; `apps/web/src/lib/http/upload-limits.ts:5`.
- Review: `docs/reviews/2026-11-10-foundation-review/web.md`, H2. The same budget is why a write the BFF gave up on may still commit in the API.

## Re-verification

Not yet verified.
