# F02 verification startup plan

Preparation only. S04 is still in progress and its handoff is not final. No product run, failure witness, manual review, pass, deferral or evidence is claimed here.

## Entry and runtime

1. Read the final feature README/requirements, all four complete stage specs/handoffs, changes since the draft, read-first documents and any bugs. Refuse feature-verify if any stage is not complete. Preserve the lead's status/handoff fields when syncing the QA branch.
2. The lead transfers service ownership after closure. Until then the developer owns API/web. Run shell commands from the QA worktree only, on `charithm/test/F02-feature-qa`. The initial runtime is the web worktree: set `QA_PRODUCT_ROOT=/Users/cmmrox/.codex/worktrees/f02-web/salon-sass` without moving or replacing retained keys. Use its existing `infra/scripts/local-service.sh` controls. Keep API 8090, web 3100 and local PostgreSQL in Docker; no remote, staging, real messages or payments.
3. Inspect the final handoff before starting. Start PostgreSQL, API, then web through the existing runtime scripts only if needed. Verify health, API meta build attribution and web build attribution against the handoff's application revisions, and verify source parity with the clean QA checkout. A successful script invocation alone is insufficient. Keep a persistent host execution session while local processes run. Do not package API artifacts while the API is running.
4. Verify the fixed synthetic alpha/beta/gamma tenants, application-role access, real application Clock, ordinary unlocked scheduler rows, retained independent four keys with mode 0600, code ceilings 3/10/20/1000, local capture/provider reset shapes and actual ignored standalone artifact location. Never print local key or developer fixture files. API tests authenticate through real password/TOTP/code APIs, not supplied or manufactured bearer tokens.
5. Use official Corepack for QA package commands. Confirm the installed pinned package runtime and Chromium driver, Docker/JDK25/local tools, curl/jq and k6. Missing installed tools are environment faults, not case deferrals. Repair QA-owned dependencies within policy; escalate system software needs with a concrete reason. Preserve the intentional QA pnpm11 negative fixture. The normal typecheck invocation is `corepack pnpm --dir qa run typecheck`.
6. Freeze the centralized browser map against the final handoff, including BFF POST success200 mapping of native DELETE204, exact routes, cookies, server log timing fields and test ids. Confirm actual DELETE JSON-body transport via the browser case, not a direct API substitution. Native deletion must invalidate both customer devices/refresh credentials while preserving the independent staff family and same-number foreign salon profile.

## Cases and the recorded run

The drafted scope has S01 30 cases, S02 18, S03 14, S04 22 and four feature cases: 87 automated and one manual. API cases cover core rules once; browser cases cover the surface, security boundary, honest tab coordination and cross-stage values. The chosen types are documented in the feature cases: API/security, tenant isolation/permissions, concurrency/data integrity/build, bounded resilience, functional/responsive/branding/accessibility and one local read-load baseline. Payments, mobile, regression, smoke and staging execution are outside this request.

At feature-verify, select suite metadata without running those suites: core automated entries belong to regression; the three account journeys can provide the feature's small smoke selection. The fixed local performance baseline remains a feature read target check; decide its release regression membership explicitly based on the catalogue. Commit cases/automation/metadata with generate/validate/QA guard before the final run and record the exact full tested QA revision. Later product or automation changes require the feature to be reverified on a new revision.

Follow `failure-witness-plan.md` for deliberate wrong-expectation witnesses on critical and count/scrape assertions. Record each actual witness and restored-file fingerprint in ignored evidence; the plan itself is not a witness. A witness whose ordinary test is broken needs triage first. Restore the exact source and ensure no temporary mutation remains before running only:

```bash
qa/scripts/sdlc run --feature F02
```

The runner executes all registered F02 automation and writes its report folder. It does not run other features. The load wrapper arranges real identities using address ceiling200 only during preparation, preserves immutable audit with actual totals >=100, proves default limits and retained keys after restart, runs ten users for twenty seconds with each read p95<300ms, observes the pool, and removes ignored authority in finally. Scheduler fixture cleanup changes only the two exactly guarded next_run_at values demonstrably advanced by the current test; unexpected state stops and reports an environment fault.

## Manual review and triage

Perform TC-F02-S04-018 on the tested revision: keyboard-only staff password/MFA, customer codes/profile, devices/privacy and Owner management, including errors and confirmation cancellation. Inspect focus visibility/order, target/contrast, small phone and front-desk tablet usability, and watch visual flows at real speed. Where live watching is unavailable, inspect timed screenshots of state transitions. Mask credentials and transient authenticator setup before any artifact is saved. Record a performed date only after the actual review.

Explore as a Sri Lankan customer on a small phone/patchy connection, receptionist on a tablet, professional between clients and Owner managing access. Compare the actual observed sign-in/permissions/device/deletion flow with public primary documentation, never using real-data accounts:

- Fresha permission roles: https://www.fresha.com/help-center/knowledge-base/team/100687-create-permission-roles
- Fresha workspace independence: https://www.fresha.com/help-center/knowledge-base/workspace-settings/101696-switch-between-workspaces
- Booksy staff permissions: https://support.booksy.com/hc/en-gb/articles/16535688940946-How-do-I-manage-staff-permissions
- Vagaro two-step verification: https://support.vagaro.com/hc/en-us/articles/115005413687-Two-Factor-or-Two-Step-Authentication
- Vagaro customer deletion: https://support.vagaro.com/hc/en-us/articles/1260806204150-Close-Your-Vagaro-Account-for-Customers-of-a-Vagaro-Business

Refresh relevant documentation when writing the final review. English-first rendered UI with centralized next-intl strings is the design scope; en/si/ta preferredLanguage persists as a profile value. A global locale chooser or rendered Sinhala/Tamil UI is not introduced as a requirement.

Triage each actual failure before rerunning: product bug, environment fault or test defect. Reproduce a product defect through the relevant user surface and file one feature bug per cause with case/criterion, exact synthetic steps, actual/expected outcome, severity and sanitized report evidence. Never fix application code or weaken an expectation. Repair and report QA-owned environment/test issues; unresolved external prerequisites mean blocked, not deferred. After developer fixes, verify the affected cases and the whole F02 feature through the lifecycle, preserving the fixed source provenance. Major/blocker bugs prevent pass.

Finish with one actual feature review, ranked must/should/idea findings and useful strengths; must findings are bugs, suggestions are proposals for the lead/owner. Report every bug/severity, environment repair, automated/manual/deferred split, exact revision/report folder and qa_status. Leave stage qa_status unchanged. Only the lead closes, submits, merges and verifies main; QA never pushes, deploys, or claims implementation completion from draft/static checks.
