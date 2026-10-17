---
id: CR-014
title: Name the authenticator step's second button for what it does
status: proposed
requested_by: QA review of F02 (SUG-F02-04)
requested_on: 2026-10-17
decided_on: null
requirements: []
features: [F02]
stages: [F02-S04]
---

# CR-014 Name the authenticator step's second button for what it does

## Request

QA's CR-013 product review of F02 (SUG-F02-04): after the password, the authenticator step shows
"Verify code" and, below it, a pill button labelled "Sign in" that abandons the challenge and
returns to the password form. Label it "Use a different account" (or "Back"), styled as a
secondary text action.

## Why

A receptionist who has just typed a password and is asked for a code sees two actions that both
sound like "continue"; pressing "Sign in" loses the step they were on. Fresha and Booksy partner
sign-in offer one primary action on the code step (`features/F02-sign-in-accounts-and-roles/review.md`).

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None changed; copy and emphasis within IAM-09 and NFR-21 |
| Features and stages | F02-S04 (complete after CR-013, unreleased): reopened for a copy/style change in the sign-in MFA step |
| Test cases and automation | Sign-in MFA expectations that click or name the button by its text update; stable test ids are unchanged |
| Data, API and released apps | None |
| Size and delay | Small: one translated label and a secondary style |

## Recommendation

Approve, and deliver it together with CR-015 as one small F02 copy repair before or alongside F03.

## Decision

Waiting for the owner.
