---
id: BUG-F01-003
title: Status success summary text fails WCAG AA contrast
severity: major
status: verified
found_by: TC-F01-S01-036
breaks: NFR-12
found_on: 2026-10-14
fixed_in: c5a18b8
fix_note: Use existing ink on unchanged positive surfaces for status and account success; failing4.4669 contrast regressions now pass, web300-test gate and live summary measurement meet AA.
developer_note: null
verified_on: 2026-10-14
approved_by: null
found_in_release: null
---

# BUG-F01-003 Status summary text fails contrast

## Steps

Role: public local visitor. Data: running local API and web, tested source
`fe53f54cdda31fdb4c222ba63f6a728e4c816a6c`.

1. Open bare-domain `http://localhost:3100/` with healthy API.
2. Read the success summary and run the existing automated WCAG AA scan.

## Expected

Normal-size status text meets4.5:1 contrast; no serious accessibility violation. The supplied
semantic palette may remain, but text pairing must obey NFR-12 and the design accessibility rules.

## Actual

Status summary uses `#0F7B55` text on `#DDF1E6`, yielding4.4669:1. Axe reports serious
`color-contrast` on the summary. Text is15px/600, below the large-text exception. Existing test
fails without any threshold modification. Observed once in the feature run and independently
confirmed by the actual palette contrast calculation.

## Evidence

`qa/reports/20261014-142945-F01/F01-S01-web-status-page.log` and its masked-free public screenshot
under `playwright/2026-10-14T09-00-32-764Z-96089/` show the failure. Seven other tests in that entry
passed; status summary contrast was the sole assertion failure.

## Re-verification

Independently verified 2026-10-14 on e4e254f4463a274fdfd055df67b3056e83a249f0. TC-F01-S01-036 and all 32 groups in qa/reports/20261014-232942-F01-2f5e0e96dfa2 passed with unchanged serious/critical axe and AA contrast criteria. Current rendered status screenshot design/F01-status-api-service-1280.png uses ink on the retained green success surface; the original 4.4669:1 failure remains preserved.
