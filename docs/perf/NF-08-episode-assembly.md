# NF-08: episode assembly at 10x peak

NF-08: episode assembly keeps up with 10x the tenant's peak hourly order rate, measured in a published load test.


## Latest recorded run (raw output committed)

`docs/perf/NF-08-run-2026-10-19.txt` is the unedited output of `scripts/perf/episode-assembly.sh` (k6 summary plus the
database check) on a fresh database: 181 orders at 1 order/s (10× a 360/h peak) for 3 minutes, 724 LIVE decisions,
724 episodes, 0 unassembled, 0 s catch-up; order capture p95 50 ms, 0% failed requests. One app instance on a laptop,
so this shows the mechanism keeps up locally; it is not a production-sized load test.

## How to run

```bash
brew install k6
BASE=http://localhost:8080 DB=oms PEAK_ORDERS_PER_HOUR=360 MULTIPLIER=10 DURATION=3m scripts/perf/episode-assembly.sh
```

`scripts/perf/episode-assembly.js` (k6) creates real orders through `POST /api/v1/orders` at a constant arrival rate of
peak x multiplier. Every order runs the normal workflow. Its decisions (fulfilment intent, sourcing node score, risk
checks) emit `decision.evaluated`, which `EpisodeAssembler` turns into episodes. When the load stops, the shell script
polls PostgreSQL until every LIVE decision of the run (injection screen excluded, as in the assembler) has an episode
for its decision point. It prints the counts and how many seconds assembly needed to catch up.

## Result (2026-10-19, local)

Machine: Apple M1 Max, 10 cores, 64 GB. One app instance (`java -Xmx1g`, default profile, no AI keys, so the heuristic
provider). PostgreSQL 16 in Docker (compose). k6 v2.3.0. Demo tenant.

| Run | Arrival rate | Duration | Orders | LIVE decisions | Episodes | Unassembled at end | Catch-up after load | Order create p95 | Errors |
|---|---|---|---|---|---|---|---|---|---|
| 10x a 360/h peak (raw output: `NF-08-run-2026-10-19.txt`) | 1 order/s (3,600/h) | 3 min | 181 | 724 | 724 | 0 | 0 s | 50.0 ms | 0% |

Earlier runs on a previous build (708/707 decisions/episodes at 1 order/s; 2,169/2,169 at 5 orders/s) were reported
without their raw output, so they are not quoted as evidence here.

In this run every LIVE decision has its own episode (724 for 724). When a decision point is evaluated twice, the second
evaluation folds into the existing episode (OC-02), so episodes can be fewer than decisions without anything missing.

## Limits

- This is a single-machine run at an assumed peak of 360 orders an hour, not a production-sized load test. Rerun
  against a deployment-sized environment, at the tenant's real peak, before quoting capacity numbers.
- Without AI keys, decisions take the heuristic path. With a live model, per-order decision latency rises, but episode
  assembly works off the ledger events afterwards, so the model's latency does not slow assembly.
