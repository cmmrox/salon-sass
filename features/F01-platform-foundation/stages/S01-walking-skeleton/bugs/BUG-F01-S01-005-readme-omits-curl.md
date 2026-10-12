---
id: BUG-F01-S01-005
title: README.md does not list curl, which local-up.sh requires and says is listed there
severity: minor
status: verified
found_by: exploratory
breaks: AC-1
found_on: 2026-10-12
fixed_in: 43d9cf1
fix_note: 'Cause: the README prerequisites table omitted curl, which local-up.sh requires. Fix: curl is now a row of the Quick start table (and 7ac555d adds the ps the scripts now need).'
developer_note: null
verified_on: 2026-10-12
approved_by: null
found_in_release: null
---

# BUG-F01-S01-005 README.md does not list curl, which local-up.sh requires and says is listed there

## Steps

Account: none. Role: a developer setting up a machine from `README.md`. Data: none.
A machine with everything the README's "Quick start" table lists (JDK 25, Node.js 24 with Corepack,
Docker running, Python) and no `curl`, as a minimal Linux image is.

1. Read the prerequisites table under "Quick start" in `README.md`.
2. Run `infra/scripts/local-up.sh`.

To reproduce on a machine that has curl, run it with a `PATH` that holds `node`, `docker` and the
basic tools but not `curl`.

## Expected

AC-1: "On a machine with the documented prerequisites, `infra/scripts/local-up.sh` starts PostgreSQL,
the API and the web app". So every tool the script refuses to start without is in the documented
list.

## Actual

The script exits 1 with `curl is required and was not found.` and `The prerequisites are listed in
README.md.` The README does not mention curl anywhere, and neither does `.env.example`. The handoff
says curl is needed and that the README's "Quick start" lists the prerequisites; the table has four
rows and curl is not one of them. Happens every time.

The message does name what is missing, so the developer is not stuck; the defect is that the
document the message points to does not agree with it.

## Evidence

```
$ infra/scripts/local-up.sh            # node 24.12.0, docker and JDK 25 present; no curl on PATH
error: a prerequisite is missing. Nothing was started.
  - curl is required and was not found.
The prerequisites are listed in README.md.
$ echo $?
1
$ grep -n -i curl README.md .env.example
$
```

`README.md`, "Quick start", the rows of the table: JDK 25; Node.js 24 or later; Docker, running;
Python 3.10 or later.

## Re-verification

Re-verified on 2026-10-12 against `43d9cf1` and `7ac555d`. TC-F01-S01-063 (`qa/checks/F01/S01-prerequisites-documented.sh`): the table under "Prerequisites, on the machine" names JDK, Node.js (with Corepack), Docker, curl and `ps`, and the reader is proved on a copy with the curl row removed. With curl, and then `ps`, taken off the PATH, `local-up.sh` exits 1, names the tool and points at README.md. Against the tree before the fix (`e04b595`) the case failed: the table named neither curl nor ps. Stage run: `qa/reports/20261012-130333-F01-S01`, PASS. Verified.
