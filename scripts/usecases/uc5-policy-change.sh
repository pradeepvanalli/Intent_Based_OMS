#!/usr/bin/env bash
# UC5 — A policy change before lunch (PRD P6, UC-10, FR-PRM-01..06, GR-10/11/14): the strategy lead edits the
# fulfilment-intent decision, simulates it on the last 30 days of real orders, gets it approved by someone else,
# rolls it out as a canary, promotes it, and can roll back with one call. No ticket to the implementer, no deploy.
source "$(dirname "$0")/lib.sh"
hr; echo "UC5 · Policy change before lunch — the policy studio lifecycle"; hr
STRAT=$(token strategy strategy); ADMIN=$(token admin admin)
NAME=fulfilment_intent

step "1) Where we are: the live definition and its analytics"
LIVE=$(api GET "/api/v1/decisions/$NAME" | j 'next((v.get("definition",v)["version"] for v in d if v.get("definition",v).get("status")=="live"), d[0].get("definition",d[0])["version"])')
api GET "/api/v1/decisions/$NAME/versions/$LIVE" | jq_ 'd=d.get("definition",d); print("    live version", d["version"], "| tier", d["tier"], "| owner", d["owner"], "| bands act ≥", d["bands"]["actGte"], "confirm ≥", d["bands"]["confirmGte"], "| status", d["status"])'
api GET "/api/v1/analytics/decisions/$NAME?days=30" | jq_ '[print("    v"+str(v["version"]), v.get("status"), "| volume", v.get("volume"), "| band mix", v.get("bandMix"), "| override", v.get("overrideRate"), "| p95", v.get("latencyP95Ms"), "ms") for v in d.get("versions",[])]'

step "2) Strategy lead drafts v$((LIVE+1)): sharper criteria for 'ship complete' (gifts) and a tighter act threshold"
YAML=$(api GET "/api/v1/decisions/$NAME/versions/$LIVE" | j 'd.get("definition",d)["yaml"]')
NEWYAML=$(printf '%s' "$YAML" | python3 -c "
import sys; y=sys.stdin.read()
y=y.replace('Everything together in one delivery, even if it arrives later','Everything together in one delivery, even if it arrives later; gifts, house moves and events usually want this')
y=y.replace('confidence_gte: 0.8','confidence_gte: 0.75',1)
y=y.replace('version: $LIVE','version: $((LIVE+1))',1)
print(y)")
DRAFT=$(AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/drafts" "$(python3 -c "import json,sys; print(json.dumps({'yaml': sys.stdin.read()}))" <<<"$NEWYAML")")
V=$(printf '%s' "$DRAFT" | j 'd.get("definition",d).get("version") or json.dumps(d)[:300]'); say "draft version $V"
step "3) Lint (overlapping options, escape option, PII, contradictions with L0 rules…)"
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/lint" '{}' | jq_ 'items=d if isinstance(d,list) else d.get("issues",[]); print("    lint findings:", len(items), [ (i.get("severity"), i.get("code")) for i in items][:6])'
step "4) Impact diff: re-run the last 30 days of real inputs through the draft — which decisions would flip, and what it costs (FR-PRM-03)"
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/impact" '{"days":30,"limit":200}' | jq_ 'print("    sampled", d.get("sampled") or d.get("evaluated"), "| flips", d.get("flips"), "| flip rate", round((d.get("flipRate") or 0)*100,1), "%"); print("    band mix before", d.get("bandMixBefore"), "→ after", d.get("bandMixAfter")); print("    escalations", d.get("escalationsBefore"), "→", d.get("escalationsAfter"), "| cost Δ $", d.get("costDeltaUsd"), "| latency Δ", d.get("latencyDeltaMs"), "ms")'
step "5) Evaluation gate: the labelled golden set must pass before anything goes live (GR-11)"
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/evaluate" '{}' | jq_ 'print("    golden set", d.get("goldenSet"), "| cases", d.get("cases"), "| accuracy", round(d.get("accuracy") or d.get("accuracyInActBand") or 0,3), "| calibration error", round(d.get("calibrationError") or 0,3), "| passed:", d.get("passed"))'
step "6) Submit with a reason and a ticket; a DIFFERENT person approves (separation of duties, GR-14)"
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/submit" '{"reason":"Gift orders were being split; sharpen ship_complete criteria and act at 0.75","ticket":"OMS-412"}' | j '"    status: " + str(d.get("definition",d).get("status"))'
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/approve" '{}' | j '"    strategy approving own change → " + str(d.get("detail") or d.get("code") or d.get("definition",d).get("status"))'
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/versions/$V/approve" '{"comment":"Reviewed impact diff, OK"}' | j '"    admin approves → status: " + str(d.get("definition",d).get("status")) + ", approvals: " + str(len(d.get("definition",d).get("approvals") or d.get("approvals") or []))'
step "7) Roll out as a 25% canary by order id, watch, then promote to live (FR-PRM-04)"
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/versions/$V/publish" '{"rollout":"canary:25"}' | j '"    → " + str(d.get("definition",d).get("status")) + " canary " + str(d.get("definition",d).get("canaryPercent")) + "%"'
say "same input evaluated against v$LIVE and v$V side by side:"
INPUT='{"input":{"order":{"channel":"web","notes":["Wedding present for my sister, please send everything together in one box"],"intent_choices":[]},"customer":{"tier":"gold","history":{"split_acceptance_rate":0.1}}},"mode":"SIMULATION"}'
for ver in $LIVE $V; do api POST "/api/v1/decisions/$NAME/evaluate-now" "$(printf '%s' "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); d['version']=$ver; print(json.dumps(d))")" | jq_ "print('    v$ver →', d.get('value'), '| band', d.get('band'), '| confidence', round(d.get('confidence') or 0,2), '| layer', d.get('layer'))"; done
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/versions/$V/publish" '{"rollout":"live"}' | j '"    → v" + str(d.get("definition",d).get("version")) + " is " + str(d.get("definition",d).get("status"))'
api GET "/api/v1/decisions/$NAME" | jq_ 'print("    versions:", [(v.get("definition",v)["version"], v.get("definition",v)["status"]) for v in d])'
step "8) Something looks off? One-click rollback, and a kill switch that drops to the deterministic default instantly (GR-06)"
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/rollback" '{"reason":"demo rollback"}' | j '"    rollback → live is now v" + str(d.get("definition",d).get("version"))'
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/kill-switch" '{"on":true,"reason":"drill"}' >/dev/null
api POST "/api/v1/decisions/$NAME/evaluate-now" "$INPUT" | jq_ 'print("    with kill switch on →", d.get("value"), "| layer", d.get("layer"), "| reason", (d.get("details") or {}).get("defaultReason"))'
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/kill-switch" '{"on":false,"reason":"drill over"}' >/dev/null
api GET "/api/v1/admin/audit?objectType=decision_definition&limit=8" | jq_ '[print("    audit:", a.get("at","")[:19], a.get("actor"), a.get("action"), a.get("reason") or "") for a in d[:8]]'
echo; say "Open $BASE/studio/$NAME → versions timeline, structured editor, lint, impact, evaluation, approvals, rollout. Talk track: idea → production in minutes, every step audited, nobody approves their own change, rollback is one click."
