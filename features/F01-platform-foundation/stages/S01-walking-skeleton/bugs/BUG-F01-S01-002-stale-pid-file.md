---
id: BUG-F01-S01-002
title: A process-id file left behind makes local-up.sh report the API as running when it is not, and local-down.sh then kills an unrelated process
severity: minor
status: verified
found_by: exploratory
breaks: AC-1
found_on: 2026-10-12
fixed_in: e092d81
fix_note: 'Cause: a pid file held only a number, so a leftover whose number had been reused was trusted. Fix: the file now records the process id and its start time (ps -p <id> -o lstart=), a service counts as running only while both match, and any other file is removed with a note while the process it names is left alone; 7ac555d makes a ps that cannot report start times a named prerequisite.'
developer_note: null
verified_on: 2026-10-12
approved_by: null
found_in_release: null
---

# BUG-F01-S01-002 A process-id file left behind makes local-up.sh report the API as running when it is not, and local-down.sh then kills an unrelated process

## Steps

Account: none. Role: a developer at a terminal. Data: none.

The situation: `.local/run/api.pid` (or `web.pid`) survives from an earlier start, because the
machine was restarted or lost power without `local-down.sh`, and the number in it now belongs to
some other process. The folder is inside the repository, so nothing clears it on a restart. To
reproduce it without restarting the machine:

1. `infra/scripts/local-down.sh`
2. Start any unrelated, long-lived process and write its id where the script keeps the API's:
   `sleep 600 & echo -n $! > .local/run/api.pid`
3. `infra/scripts/local-up.sh`
4. `curl -i http://localhost:8090/actuator/health`, and open `http://localhost:3100/`.
5. `infra/scripts/local-down.sh`
6. Look for the `sleep` process.

## Expected

AC-1: `local-up.sh` starts the API, or exits non-zero and says why it could not. It reports a service
as already running only when that service is. `local-down.sh` stops only what the scripts started
(its own comment says: "Only ever a pid we recorded: a process that merely holds the same port
belongs to someone else").

## Actual

Step 3 prints `api: already running`, prints the API's address and exits 0. The API is not running:
nothing listens on port 8090 and the web app shows its "Service unavailable" state. Step 5 prints
`api: stopped` and the unrelated process is gone: it was sent TERM, and its children too.
Happens every time the file names a live process.

How often a developer meets it: only when a left-over id has been reused by a live process, which
is uncommon; but when it happens the script kills something that is not the product's, without
saying so.

## Evidence

```
$ sleep 600 & echo -n $! > .local/run/api.pid        # the unrelated process is 68535
$ infra/scripts/local-up.sh
postgres: started
api: already running
web: installing dependencies
web: building
web: started

PostgreSQL  localhost:5440  (database salon, user salon)
API         http://localhost:8090  (health: http://localhost:8090/actuator/health)
Web         http://localhost:3100
$ echo $?
0
$ curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8090/actuator/health
000
$ infra/scripts/local-down.sh
web: stopped
api: stopped
postgres: stopped and removed; its data is kept
$ kill -0 68535
kill: (68535): No such process
```

## Re-verification

Re-verified on 2026-10-12 against `e092d81` and `7ac555d`. The bug's own steps as TC-F01-S01-060 (`qa/checks/F01/S01-stale-pid-file.sh`): a bare id of a live unrelated process in `api.pid` and a wrong start time in `web.pid`. `local-up.sh` said `api: started` and `web: started`, never `already running`, noted the leftover `api.pid`, and health answered 200. `local-down.sh` left both unrelated processes alive. A leftover naming an ended process, and `stop web` with a leftover `web.pid`, behaved the same. Against the tree before the fix (`e04b595`) the case failed exactly as filed: `already running` with health 000, and the unrelated process stopped. Stage run: `qa/reports/20261012-130333-F01-S01`, PASS. Verified.
