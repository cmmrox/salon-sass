---
id: CR-005
title: Local HTTP with TLS verified only on staging
status: approved
requested_by: owner
requested_on: 2026-10-13
decided_on: 2026-10-13
requirements: []
features: [F01]
stages: [F01-S05]
---

# CR-005 Local HTTP with TLS verified only on staging

## Request

"No need to check TLS certificate or SSL in local; remove those testing or development on that. In local we don't need to test SSL or TLS; we'll test it once it is put on the staging VPS. There is no need to set those things up locally."

## Why

The owner wants local development and feature QA to exercise application routing and deployment
behavior without provisioning or trusting local certificates. TLS belongs to the real staging edge
and will be verified after the owner requests a VPS staging deployment.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | BRD-03 and NFR-07 remain unchanged: deployed salon subdomains still require secure transport. Only local TLS setup and proof are removed. |
| Features and stages | F01 is `in-progress`; reopen complete F01-S05 under this request, rebuild its local stack behavior, and close it again before F01 QA. |
| Test cases and automation | Local stack routing and traffic checks use HTTP. Remove local certificate trust, HTTPS, redirect and HSTS assertions. Keep public TLS verification in the existing deferred `TC-F01-002`, to be run only after an explicitly requested VPS staging deployment. |
| Data, API and released apps | No schema, API or client change. Use a local HTTP edge configuration and retain a separate staging configuration with public TLS. No local CA, trust installation or certificate volume is required. |
| Size and delay | Small infrastructure/configuration and QA update; rebuild and reverify F01-S05 and rerun full F01 QA. This delays the F01 merge until those gates pass. |

## Recommendation

Approve. Keep HTTPS configured for the real staging edge, but make local development and QA HTTP-only. This avoids spending local effort on certificates while preserving an explicit staging TLS gate.

## Decision

Approved by the owner on 2026-10-13. Apply to F01-S05 before full feature QA and closure.
