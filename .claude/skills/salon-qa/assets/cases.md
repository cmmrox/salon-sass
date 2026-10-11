# F00-S01 <stage title>: test cases

## Test approach

| Test type | Why it applies to this stage |
| --- | --- |
| api | <reason> |
| <another type from docs/qa/test-types.md> | <reason> |

Not applied, and why: <types from docs/qa/test-types.md that were considered and ruled out>.

Environment: local. Test data and accounts (from docs/qa/test-plan.md): <what the cases rely on>.

## Cases

Every case id here is declared under `cases:` in `stage.yaml`, and the reverse.

### TC-F00-S01-001 <what is proved, as a statement>

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** <which account and role>
- **Preconditions:** <state that must exist first>
- **Steps:**
  1. <step>
  2. <step>
- **Expected:** <the observable outcome, including what must not happen>
- **Automation:** `<path under qa/>`

### TC-F00-S01-002 <a negative or boundary case>

- **Type:** <type>
- **Priority:** critical
- **Covers:** AC-2
- **Performed as:** <one organisation's user, against a record created by another>
- **Preconditions:** <the other record exists, and its owner can read it>
- **Steps:**
  1. <step>
- **Expected:** <"not found"; nothing about the other record is revealed or changed>
- **Automation:** manual, because <reason>; performed on <date>   <!-- or: deferred, because <reason>; unblocked by <stage or event> -->
