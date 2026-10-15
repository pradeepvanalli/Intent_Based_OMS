#!/usr/bin/env bash
# Policy studio lifecycle for one decision: draft → lint → impact → evaluate → submit → approve (different user) →
# canary → live → rollback (FR-PRM-01..06, GR-10/11/14). Mirrors scripts/usecases/uc5-policy-change.sh.
# Usage: NAME=fulfilment_intent ./studio_lifecycle.sh   (needs the demo users strategy/strategy and admin/admin)
source "$(dirname "$0")/_lib.sh"
NAME=${NAME:-fulfilment_intent}
STRAT=$(login strategy strategy); ADMIN=$(login admin admin)

step "Live version"
LIVE=$(api GET "/api/v1/decisions/$NAME" | j 'next((v.get("definition",v)["version"] for v in d if v.get("definition",v).get("status")=="live"), d[0].get("definition",d[0])["version"])')
YAML=$(api GET "/api/v1/decisions/$NAME/versions/$LIVE" | j 'd.get("definition",d)["yaml"]')
echo "live v$LIVE"

step "Draft: tighten the act threshold (POST /decisions/$NAME/drafts {yaml})"
NEWYAML=$(printf '%s' "$YAML" | python3 -c 'import sys; y=sys.stdin.read(); print(y.replace("confidence_gte: 0.8","confidence_gte: 0.85",1))')
V=$(AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/drafts" "$(python3 -c 'import json,sys; print(json.dumps({"yaml": sys.stdin.read()}))' <<<"$NEWYAML")" | j 'd.get("version") or d.get("definition",d).get("version") or json.dumps(d)[:200]')
echo "draft v$V"

step "Lint, impact over 30 days, golden-set evaluation"
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/lint" '{}' | j '"lint ok="+str(d.get("ok"))+" issues="+str([(i["severity"],i["code"]) for i in d.get("issues",[])])'
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/impact" '{"days":30,"limit":200}' | j '"sampled="+str(d.get("sampled"))+" flips="+str(d.get("flips"))+" bandMixAfter="+str(d.get("bandMixAfter"))'
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/evaluate" '{}' | j '"evaluation passed="+str(d.get("passed"))+" accuracy="+str(d.get("accuracyInActBand") or d.get("accuracy"))+" calibrationError="+str(d.get("calibrationError"))'

step "Submit (reason + ticket mandatory); the submitter is refused as approver; admin approves"
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/submit" '{"reason":"Act only at 0.85 after reviewing flips","ticket":"OMS-EX-1"}' | j '"status="+str(d.get("definition",d).get("status"))'
AS_TOKEN=$STRAT api POST "/api/v1/decisions/$NAME/versions/$V/approve" '{}' | j '"self-approve → "+str(d.get("detail") or d.get("code"))'
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/versions/$V/approve" '{}' | j '"status="+str(d.get("definition",d).get("status"))'

step "Canary 25% → live → rollback"
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/versions/$V/publish" '{"rollout":"canary:25"}' | j '"status="+str(d.get("definition",d).get("status"))+" canary="+str(d.get("definition",d).get("canaryPercent"))'
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/versions/$V/publish" '{"rollout":"live"}' | j '"status="+str(d.get("definition",d).get("status"))'
AS_TOKEN=$ADMIN api POST "/api/v1/decisions/$NAME/rollback" '{"reason":"example rollback"}' | j '"live is v"+str(d.get("definition",d).get("version"))'
api GET "/api/v1/admin/audit?objectType=decision_definition&limit=6" | j '[(a.get("action"), a.get("actor")) for a in d]'
