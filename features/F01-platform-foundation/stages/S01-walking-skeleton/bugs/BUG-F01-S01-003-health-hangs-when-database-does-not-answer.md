---
id: BUG-F01-S01-003
title: Health hangs for as long as PostgreSQL accepts connections but does not answer
severity: minor
status: verified
found_by: exploratory
breaks: AC-2
found_on: 2026-10-12
fixed_in: 73ef051
fix_note: 'Cause: Spring Boot db health check calls Connection.isValid with no time limit on a pooled connection, and the driver had no socket or login timeout. Fix: the db component is now DatabaseHealthIndicator, whose SELECT 1 runs under the pool validation timeout (1 s) so the connection fails, is discarded and health recovers on its own; the driver also has loginTimeout 2 s and socketTimeout 30 s. Health answers 503 in 1 to 2 s with PostgreSQL paused and 200 at once when it resumes.'
developer_note: null
verified_on: 2026-10-12
approved_by: null
found_in_release: null
---

# BUG-F01-S01-003 Health hangs for as long as PostgreSQL accepts connections but does not answer

## Steps

Account: none. Role: any caller of the API (in practice a load balancer's health probe). Data: none.
The product is running (`infra/scripts/local-up.sh`).

1. `curl http://localhost:8090/actuator/health` three times, so the API holds a connection to the database.
2. Freeze PostgreSQL without closing its connections: `docker pause salon-postgres`. This stands for a
   database that is overloaded, or behind a network that silently drops packets: connections stay
   open and nothing comes back. (`infra/scripts/local-service.sh` has no such control; `docker pause`
   on this project's own container was used by hand.)
3. `curl -s -o /dev/null -w '%{http_code} after %{time_total}s\n' --max-time 240 http://localhost:8090/actuator/health`
4. Repeat step 3 from a second terminal while the first is still waiting.
5. `docker unpause salon-postgres`

## Expected

AC-2 says of a stopped PostgreSQL that health "returns 503 within 5 seconds and does not hang". A
database that has stopped answering is down in every way that matters to a caller, and the same
holds: 503 within 5 seconds. The risk the spec names is "a dependency being down takes the service
down, or hangs it".

## Actual

With PostgreSQL stopped (connections refused) health behaves as AC-2 says: 503 in 2.0 s
(TC-F01-S01-024, -025 pass). With PostgreSQL frozen, the first health request never answers: no
response in 240 s, and it returns only when the database is unfrozen. Requests made while it waits
answer 503 in 2.0 s. Happens every time: 2 times in 2.

The request that hangs is the one that was handed a connection the pool already held; its query
waits on the socket with no limit. Later requests fail fast only because the pool has nothing left
to hand out. From F01-S02 every ordinary request will hold a connection the same way.

Severity is minor because AC-2 as written (PostgreSQL stopped) is met and the hang is limited to as
many requests as the pool has connections. QA's view is that it should be fixed before the first
endpoint that reads the database exists; see SUG-F01-S01-03 in `review.md`.

## Evidence

```
warm: 200
warm: 200
warm: 200
frozen
first health while frozen: 000 after 240.002979s        # curl gave up; the API had not answered
second: 503 after 2.025547s
unfrozen
after: 200 0.005552s
```

`.local/logs/api.log`, written when the database was unfrozen:

```
WARN ... o.s.b.j.h.DataSourceHealthIndicator      : DataSource health check failed
WARN ... o.s.b.h.a.e.HealthEndpointSupport        : Health contributor ...DataSourceHealthIndicator (db) took 242131ms to respond
```

## Re-verification

Re-verified on 2026-10-12 against `73ef051`, with the new hook `infra/scripts/local-service.sh pause|resume postgres` (`e5f827c`) instead of `docker pause`. TC-F01-S01-061 (`qa/checks/F01/S01-database-frozen.sh`) asks for health every 0.1 s across the moment of the freeze, which is what makes the old hang appear: 84 requests, all 200 or 503, the slowest 2.0 s. After that, 503 in 2.0 s throughout (fifty at once included, and 35 s in, past the 30 s socket limit), meta 200, and 200 within a second of `resume` from the same API process. Against the API before the fix (`e04b595`) the same case failed: one request across the freeze got no answer in 60 s. A first version of the case, which froze the database and only then asked, passed on the old API, because the pool tests a connection idle for more than half a second; it was rewritten before it was trusted. Stage run: `qa/reports/20261012-130333-F01-S01`, PASS. Verified.
