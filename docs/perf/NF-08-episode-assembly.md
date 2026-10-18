# NF-08: episode assembly at 10x peak

NF-08: episode assembly keeps up with 10x the tenant's peak hourly order rate, measured in a published load test.

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
| 10x a 360/h peak | 1 order/s (3,600/h) | 3 min | 181 | 708 | 707 | 0 | 0 s | 43.6 ms | 0% |
| 10x a 1,800/h peak | 5 orders/s (18,000/h) | 2 min | 601 | 2,169 | 2,169 | 0 | 1 s | 24.6 ms | 0% |

In the first run, 707 episodes for 708 decisions means one decision point was evaluated twice and folded into one
episode (OC-02), as designed. Every decision was the anchor or the latest evaluation of an episode.

## Limits

- This is a single-machine run, not a production-sized load test. The second row is 5x the first and still shows no
  assembly backlog. Rerun against a deployment-sized environment before quoting capacity numbers.
- Without AI keys, decisions take the heuristic path. With a live model, per-order decision latency rises, but episode
  assembly works off the ledger events afterwards, so the model's latency does not slow assembly.
