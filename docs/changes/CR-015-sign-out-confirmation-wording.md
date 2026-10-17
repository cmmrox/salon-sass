---
id: CR-015
title: Say what a sign-out confirmation will sign out
status: proposed
requested_by: QA review of F02 (SUG-F02-05)
requested_on: 2026-10-17
decided_on: null
requirements: []
features: [F02]
stages: [F02-S04]
---

# CR-015 Say what a sign-out confirmation will sign out

## Request

QA's CR-013 product review of F02 (SUG-F02-05): "Sign out all devices" opens a drawer titled
"Confirm this action" with the line and button "Confirm sign-out", and one device uses the same
wording. Title and button from the action instead: "Sign out all devices?" with "You will be
signed out here and on every other device", and "Sign out this device?" for one.

## Why

Before anything irreversible, what is about to happen must be plain. An owner signing out every
device, including front-desk tablets, should see that it is every device.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None changed; clarity within IAM-09 and NFR-21 |
| Features and stages | F02-S04 (complete after CR-013, unreleased): reopened for confirmation copy |
| Test cases and automation | Confirmation expectations that read the title or button text update; behaviour and test ids are unchanged |
| Data, API and released apps | None |
| Size and delay | Small: translated strings per confirmation |

## Recommendation

Approve, and deliver it together with CR-014.

## Decision

Waiting for the owner.
