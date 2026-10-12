---
id: BUG-F01-S01-001
title: Two runs of local-up.sh that overlap both fail, leave the product stopped, and can leave an API process the scripts no longer stop
severity: minor
status: fixed
found_by: exploratory
breaks: AC-1
found_on: 2026-10-12
fixed_in: 193a982
fix_note: 'Cause: nothing stopped two local-*.sh runs from building into the same target/, writing the same pid file and stopping each other. Fix: local-up, local-down, local-reset and local-service now take a lock (.local/run/lock, naming its holder by process id and start time) before starting or stopping anything; a second run prints waiting for another of these scripts to finish, then finds everything running and exits 0; a lock left by a killed script is recognised and removed.'
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-S01-001 Two runs of local-up.sh that overlap both fail, leave the product stopped, and can leave an API process the scripts no longer stop

## Steps

Account: none. Role: a developer at a terminal (or two: a person and an agent, or two terminals). Data: none.
The product is stopped (`infra/scripts/local-down.sh`); the prerequisites are all present.

1. Run `infra/scripts/local-up.sh`.
2. While it is still printing `api: building` (4 to 6 seconds later), run `infra/scripts/local-up.sh` again in a second terminal.
3. Wait for both to return, then request `http://localhost:8090/actuator/health` and `http://localhost:3100/`.
4. Run `infra/scripts/local-down.sh`, then `infra/scripts/local-up.sh` once, alone.

In one command:

```bash
infra/scripts/local-down.sh
(infra/scripts/local-up.sh; echo "first exit=$?") & sleep 5; (infra/scripts/local-up.sh; echo "second exit=$?") & wait
```

## Expected

AC-1: `local-up.sh` starts PostgreSQL, the API and the web app. A second start while one is in progress
must not undo the first: it waits for it, or says that a start is already in progress and stops, or
finds everything running and exits zero (as a second start does once the first has finished,
TC-F01-S01-004). At the end the product is running, and `local-down.sh` stops everything that was started.

## Actual

Both runs exit 1 with `error: api did not come up at http://localhost:8090/actuator/health`, and the
product is not running: health and the web app do not answer. Each run builds the API into the same
`target/`, writes the same `.local/run/api.pid` and `.local/logs/api.log`, and, when its own wait
fails, stops the API the other one started. Happens every time the second start falls inside the
first one's API build: 3 times in 3 (second start 4 s, 4 s and 6 s after the first).

In 1 of those 3 an API process was left running that the scripts had lost track of:
`local-down.sh` printed `api: not running` and left it listening on port 8090, and the next
`local-up.sh` refused to start with a message that blames another program. It had to be found and
killed by hand.

When the two starts begin at the same instant the outcome is different and milder: one succeeds,
the other exits 1 with Docker's own words (`Conflict. The container name "/salon-postgres" is
already in use`) under `error: postgres did not start`, although PostgreSQL did start.

## Evidence

Second start 6 s after the first (2026-10-12):

```
first exit=1, second exit=1; health 000, web 000
error: api did not come up at http://localhost:8090/actuator/health. The last lines of .local/logs/api.log: ...

$ infra/scripts/local-down.sh
web: not running
api: not running
postgres: stopped and removed; its data is kept

$ lsof -nP -iTCP:8090 -sTCP:LISTEN
java 95060 ... TCP *:8090 (LISTEN)        # java -jar target/salon-api.jar, parent process 1, no pid file in .local/run

$ infra/scripts/local-up.sh
postgres: started
error: api cannot start: port 8090 is already in use by something this script did not start. Free it, or set SALON_API_PORT to another port (see .env.example).
```

The log of one of the two runs shows the API it was waiting for being shut down from outside:
`Started SalonApiApplication in 1.824 seconds` followed 1 ms later by `Commencing graceful shutdown`.

## Re-verification

