---
id: BUG-F01-004
title: Invalid bearer rejection omits the required request log
severity: major
status: open
found_by: TC-F01-S04-014
breaks: NFR-16
found_on: 2026-10-14
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-004 Invalid bearer rejection omits the required request log

## Steps

Role: public local caller. Data: active synthetic Gamma and the local API built from
`3861b3d03f9124f4df2198a957456c8bdcbbd4b6`; no real credential or personal data.

1. GET `http://gamma.localhost:8090/api/v1/salon` with a unique valid W3C traceparent,
   synthetic query/Cookie markers and no Authorization. Confirm Gamma's 200 response and its
   corresponding request log with the caller trace, Gamma tenant, method, path, status and duration.
2. Repeat on Gamma's actual hostname with a different trace and a synthetic invalid Bearer value.
3. Read the 401 problem's traceId and inspect ECS request records for both the caller and response
   trace in `.local/logs/api.log`.

## Expected

The invalid credential returns generic 401 `invalid_session` without salon data, under the
approved F02 contract. F01-S04 AC-14 and `docs/engineering/backend.md` require each request to
write one redacted method/path/status/duration line, continuing the caller trace; an error response
   must not disappear from request observability. The rejection happens before tenant binding,
   so the denied line has no `tenant.id`; logging must not add a registry lookup or trust the
   invalid credential. The valid noncredential control retains Gamma's exact tenant tag.

## Actual

The noncredential control returns Gamma's 200 and exactly one correctly tagged request line.
The invalid Bearer returns 401 `invalid_session` with the exact caller traceId, but there is no
request record for that trace. The denial therefore lacks the required status/path/duration log.
Confirmed on the real Gamma URL; the earlier diagnostic's localhost Host override was not a
valid selected-salon control and is retained separately. This defect is distinct from the old
test's obsolete expectation that an invalid credential would return 200.

## Evidence

`qa/reports/20261014-164715-F01/S04-014-actual-host-log-reproduction.json` contains the positive
control, exact matching caller/response trace, 401 outcome and empty denied-request record set.
The initial failure is preserved in `F01-S04-platform-services.log`. Synthetic marker values and
credential bytes are absent from the diagnostic evidence.

## Re-verification

Pending developer fix. Retain exact 200 tenant/trace logging and invalid-credential 401/redaction
assertions, then verify this case and the complete F01/F02 scopes on the committed final source.
