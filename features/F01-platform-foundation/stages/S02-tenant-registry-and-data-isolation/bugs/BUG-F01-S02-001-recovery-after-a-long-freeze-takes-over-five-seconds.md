---
id: BUG-F01-S02-001
title: After a 40-second freeze of PostgreSQL the API answered nothing but 503 until 5017 ms and 5064 ms after resume was asked for, in two of four such thaws; the limit is 5000 ms
severity: minor
status: open
found_by: TC-F01-S02-052
breaks: AC-10
found_on: 2026-10-12
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-S02-001 After a 40-second freeze of PostgreSQL the API answered nothing but 503 until 5017 ms and 5064 ms after resume was asked for, in two of four such thaws; the limit is 5000 ms

Severity, and why: `minor`. For the people who use the product the effect is a few more seconds of
"temporarily unavailable" at the end of a database outage that had already lasted 40 seconds; the
product then recovers by itself, without a restart, and no salon is ever answered with anything but
its own brand. The limit is passed by 17 ms and 64 ms on the strictest reading of where the 5 seconds
start, and is met (4279 ms) when they are counted from the resume command returning.

## Steps

Account: none (nothing signs in yet). Role: none. Data: the three seeded salons (alpha, beta, gamma),
the product started with `infra/scripts/local-up.sh`.

The steps are those of `qa/checks/F01/S02-database-silent.sh`, run by `qa/scripts/sdlc run --stage F01-S02`:

1. Keep 24 callers asking `GET /api/v1/salon` on `alpha.localhost:8090`, `beta.localhost:8090` and
   `gamma.localhost:8090` without a break, each asking again 25 ms after it is answered.
2. `infra/scripts/local-service.sh pause postgres`, wait 7 s, `infra/scripts/local-service.sh resume postgres`,
   wait 8 s. (The short freeze. Recovery after it has always been well inside the limit.)
3. `infra/scripts/local-service.sh pause postgres` again and leave it frozen for about 40 s, which is
   past the 30 s at which the database driver gives up on a socket.
4. Note the time, then `infra/scripts/local-service.sh resume postgres`, and note when it returns.
5. Find the first request answered 200 after that time, and how long after it the answer came.

## Expected

AC-10: "Within 5 seconds of `resume postgres` it answers 200 again without the API being restarted."
The check counts the 5 seconds from the moment the resume command was asked for (step 4), so the
command's own time to thaw the container counts against the product. That is the strictest reading
of the criterion; whether it is the right one is the owner's to say.

## Actual

After the long freeze the first 200 came 5017 ms (run of 17:26) and 5064 ms (run of 18:55) after the
resume command was asked for: 17 ms and 64 ms over the limit. Until then every request was answered
503 `service_unavailable`. From then on every salon was answered with its own brand, the API was
not restarted, and nothing was answered with another salon's data.

How often: twice in the eight thaws measured (four runs of the check, two thaws each). Both were
thaws after a 40-second freeze, so twice in four of those, and never in the four thaws after a
7-second freeze. The other two long thaws recovered in 728 ms and 424 ms: the result is one or the
other, about half a second or about five seconds, and nothing in between has been seen.

What is known since the run of 18:55, which recorded the marks: the resume command returned 785 ms
after it was asked for and the container was confirmed no longer paused at 820 ms. The API went on
answering only 503 for a further 4244 ms. So about 0.8 s of the 5064 ms was the test's own command
and about 4.2 s came after it.

What is still not known:

- Whether those 4.2 s were the API failing to get a working connection from its pool, or PostgreSQL
  not yet accepting connections although its container was no longer paused. The check has no probe
  of PostgreSQL that does not go through the API, and the API's log of the thaw is gone from both
  failing runs: later entries of the same stage run restart the API, and that overwrites
  `.local/logs/api.log`.
- For the run of 17:26, how its 5017 ms split: it recorded no marks.

A lead for the developer, not verified: in both failing runs the pattern is the same to within 25 ms
(see Evidence), which points at a fixed wait rather than at load. The pool that holds the API's
connections waits between attempts to open a new one after failures; if that wait has grown to
seconds by the fortieth second of an outage, recovery would take anything up to that long depending
on where in the wait the thaw falls.

## Evidence

Recovery time is the time from the resume command being asked for to the first 200.

| Run folder | After the 7 s freeze | After the 40 s freeze | Resume command returned after |
| --- | --- | --- | --- |
| `qa/reports/20261012-170409-F01-S02` (pass) | 590 ms | 728 ms | not recorded |
| `qa/reports/20261012-172658-F01-S02` (fail) | 520 ms | **5017 ms** | not recorded |
| `qa/reports/F01-S02-resume-timing-investigation` | 358 ms | 424 ms | 346 ms and 419 ms |
| `qa/reports/20261012-185529-F01-S02` (fail) | 364 ms | **5064 ms** | 387 ms and 785 ms |

Two other folders, `qa/reports/20261012-170219-F01-S02` and `qa/reports/20261012-171939-F01-S02`,
measured no thaw: the API was not reachable in them (an environment fault, recorded in `review.md`).

The run of 18:55, `qa/reports/20261012-185529-F01-S02/F01-S02-database-silent.log`:

```
ok: after the first freeze: the first 200 came 364 ms after 'resume postgres' was asked for
WRONG: after the long freeze: the first 200 came '5064' ms after 'resume postgres' was asked for, the limit is 5000 ms
    first resume control returned 387 ms after invocation; PostgreSQL was confirmed thawed after 444 ms
    long-freeze resume control returned 785 ms after invocation; PostgreSQL was confirmed thawed after 820 ms
```

Its `database-silent-marks.jsonl`: `second_frozen` 1791811587267, `second_thaw_asked` 1791811627385
(a freeze of 40118 ms), `resume_returned_2` 1791811628170, `second_thawed` 1791811628205. The first
200 ended at 1791811632449: 4279 ms after the command returned, 4244 ms after the container was
confirmed thawed.

The two failing runs side by side, from each run's `database-silent-stream.jsonl`. Times are from
the moment the resume command was asked for (in the run of 17:26, the first 200's end less 5017 ms):

| | Run of 17:26 | Run of 18:55 |
| --- | --- | --- |
| Requests that began after the thaw was asked for and were answered 503 | 40, each after 2007 to 2011 ms | 40, each after 2004 to 2010 ms |
| The last of them began at, and was answered at | 2543 ms, 4553 ms | 2545 ms, 4553 ms |
| The first request to be answered 200 began at, and took | 3553 ms, 1464 ms | 3529 ms, 1535 ms |
| First 200 | 5017 ms | 5064 ms |
| 200 answers in the following second | 820 | 802 |

Each of those 503s took just over 2000 ms, which is how long the API's pool waits for a connection
(`connection-timeout: 2000` in `application.yaml`), so each looks like a request that waited for a
database connection and was given none. The requests that were answered 200 first had all been
waiting since about 3.5 s and were all answered together at about 5.0 s. That is an observation,
not a diagnosis.

In the instrumented run the resume command took 346 ms and 419 ms and the first 200 came 12 ms and
5 ms after it returned: when recovery is quick, the API is answering as soon as the container is thawed.

## Re-verification

<QA, after a fix: what was rerun, the run folder, and the result.>
