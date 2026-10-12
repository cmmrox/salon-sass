---
id: CR-002
title: Have the local API and web app listen on this machine only
status: implemented
requested_by: QA review of F01-S01
requested_on: 2026-10-12
decided_on: 2026-10-12
requirements: []
features: [F01]
stages: [F01-S02]
---

# CR-002 Have the local API and web app listen on this machine only

## Request

From `SUG-F01-S01-02`: in the local environment, bind the API and the web app to 127.0.0.1, as PostgreSQL already is.

## Why

Both currently listen on every network interface, so on a shared network a developer's laptop serves the product, with its local-only database password, to anyone who can reach it. From F01-S02 that includes salon data, even if synthetic.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None changed. Supports NFR-07 (least exposure) |
| Features and stages | A change to the local scripts and settings; done with F01-S02, which next touches them. F01-S01 is not reopened |
| Test cases and automation | One shell check that the two ports are not reachable on a non-loopback address |
| Data, API and released apps | None. Testing the web app from a phone on the same network would need an explicit opt-in variable |
| Size and delay | Very small. No delay |

## Recommendation

Approve, with an opt-in variable for testing from a real phone.

## Decision

Approved by the owner on 2026-10-12, as recommended, with an opt-in variable for testing from a real phone: built in F01-S02.

Implemented in F01-S02; independent QA passed on 2026-10-12. Both services default to IPv4/IPv6
loopback, and the opt-in and return to loopback were verified. Evidence:
`qa/reports/20261012-170409-F01-S02`.
