---
id: CR-030
title: A salon's public photos may be kept by the visitor's own browser
status: approved
requested_by: lead, from the web data layer refactor (decision 127); raised by QA against F04-S02 AC-7
requested_on: 2026-11-10
decided_on: 2026-11-10
requirements: [BRD-01, CAT-01, STF-01]
features: [F04, F05, F06]
stages: [F04-S02, F05-S01, F06-S01]
---

# CR-030 A salon's public photos may be kept by the visitor's own browser

## Request

While building the web data layer (decision 127), the lead allowed a salon's public photos to be kept by the
visitor's own browser. QA pointed out that F04-S02 AC-7 says "Every image response carries the salon hostname's
no-store caching", so the product and an approved criterion disagreed, and only the owner changes a criterion.

## Why

Each photo cost two API calls every time it was shown, because nothing about a salon was ever cached. That is felt
most on the customer booking page (F24), where service and team photos load over mobile data on small phones. A
photo is addressed by an id that a new upload replaces, so the same address never shows a different picture.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None added. How BRD-01 (logo and cover), CAT-01 (service image) and STF-01 (team photo) are served changes |
| Features and stages | F04-S02 (AC-7's caching sentence is replaced by AC-13), F05-S01 (AC-21) and F06-S01 (AC-18): all merged, not released, so the change ships as a follow-up under decision 098. It is already built in PR #31, which is that follow-up |
| Test cases and automation | The three cases that asserted `no-store` on a 200 image answer assert the new headers; QA adds the cases for a draft, a 404, a suspended salon and another salon's hostname |
| Data, API and released apps | Response headers only. Nothing is released |
| Size and delay | None: built with package 5 of the foundation review |

## Recommendation

Approve. No shared cache may keep a photo, the browser keeps it under the salon's own address, and a person's
photo is kept for an hour only, so one that is removed stops showing within the hour.

## Decision

Approved by the owner on 2026-11-10: a salon's public photos (service photos, team photos, the published logo and
cover) may be kept in the viewer's own browser, never in any shared cache, for up to a day, and for up to an hour
for a person's photo. A removed photo can therefore still show from a browser's own cache for that long.
