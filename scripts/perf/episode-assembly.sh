#!/usr/bin/env bash
# NF-08: run the k6 episode-assembly load and measure, from the database, whether episode assembly kept up.
#
#   scripts/perf/episode-assembly.sh                      # defaults: BASE=http://localhost:8080, 360 orders/h peak x 10, 5 minutes
#   BASE=http://localhost:8105 DB=oms_f5 DURATION=3m scripts/perf/episode-assembly.sh
#
# Needs k6 (brew install k6) and the compose PostgreSQL (container oms-postgres-1). Prints the k6 summary, then:
#   live_decisions      LIVE decision records written for this run's orders
#   assembled           of those, how many are the anchor or the latest evaluation of an episode
#   unassembled         the rest, polled until zero or LAG_TIMEOUT seconds
#   catch_up_seconds    seconds after the load ended until nothing was left unassembled
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASE=${BASE:-http://localhost:8080}
TENANT=${TENANT:-demo}
DB=${DB:-oms}
PG=${PG:-oms-postgres-1}
LAG_TIMEOUT=${LAG_TIMEOUT:-600}
RUN_ID=${RUN_ID:-$(date +%s)}
export BASE TENANT RUN_ID

psqlq() { docker exec "$PG" psql -U oms -d "$DB" -Atc "$1"; }

command -v k6 >/dev/null || { echo "k6 not found: brew install k6" >&2; exit 1; }
k6 run "$ROOT/scripts/perf/episode-assembly.js"
ENDED=$(date +%s)

COUNTS="
  with run_orders as (select id from orders where tenant_id = '$TENANT' and channel_order_id like 'NF08-$RUN_ID-%'),
       recs as (select d.id from decision_record d join run_orders o on (o.id = d.subject_id or o.id = d.correlation_id) where d.tenant_id = '$TENANT' and d.mode = 'LIVE'
                and d.decision <> 'injection_screen')
  select (select count(*) from run_orders), (select count(*) from recs),
         (select count(*) from recs r where exists (select 1 from episode e where e.tenant_id = '$TENANT' and (e.decision_id = r.id or e.last_decision_id = r.id))),
         (select count(*) from episode e where e.tenant_id = '$TENANT' and e.order_id in (select id from run_orders))"
while :; do
  IFS='|' read -r orders live assembled episodes <<<"$(psqlq "$COUNTS")"
  # A re-evaluation folded into an episode is not its anchor or latest record; count it as assembled when its decision point has an episode.
  unassembled=$(psqlq "
    with run_orders as (select id from orders where tenant_id = '$TENANT' and channel_order_id like 'NF08-$RUN_ID-%')
    select count(*) from decision_record d join run_orders o on (o.id = d.subject_id or o.id = d.correlation_id)
    where d.tenant_id = '$TENANT' and d.mode = 'LIVE' and d.decision <> 'injection_screen'
      and not exists (select 1 from episode e where e.tenant_id = d.tenant_id and e.decision_name = d.decision and e.order_id = o.id)")
  now=$(date +%s)
  if [ "$unassembled" = "0" ] || [ $((now - ENDED)) -ge "$LAG_TIMEOUT" ]; then break; fi
  sleep 2
done
echo "run_id=$RUN_ID orders=$orders live_decisions=$live episodes=$episodes anchor_or_latest=$assembled unassembled=$unassembled catch_up_seconds=$(( $(date +%s) - ENDED ))"
