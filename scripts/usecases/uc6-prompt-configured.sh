#!/usr/bin/env bash
# UC6 — The OMS you configure by talking to it (FR-CFG-01/02, FR-SM-12, UC-10/11, GR-14): a merchandising lead types
# three policies in plain English. The copilot turns them into validated, diffed, tested DRAFTS across decisions, rules
# and state models; a human reviews and stages them; publishing still runs through lint → golden set → approval.
source "$(dirname "$0")/lib.sh"
hr; echo "UC6 · Configure the OMS by talking to it — plain-English policies → governed drafts"; hr
api GET /api/v1/copilot/status | jq_ 'print("    copilot:", "available" if d.get("available") else "UNAVAILABLE (set OPENROUTER_API_KEY)", "| model", d.get("model"), "| budget left today $"+str(round(d.get("remainingUsd") or 0,2)))'
STRAT=$(token strategy strategy)

step "1) The request, exactly as a business user would type it"
REQ="Gold customers with a receipt get an instant refund for returns under \$250. Never split an order that contains hazmat items. Agent-placed orders over \$500 shipping to California must go through a risk review before release."
echo "    \"$REQ\""
step "2) Copilot proposes a ChangeSet (this is a real LLM call; expect 30–90 s)"
START=$(date +%s)
BODY=$(REQ_TEXT="$REQ" python3 -c 'import json,os; print(json.dumps({"text": os.environ["REQ_TEXT"], "scope": "all"}))')
CS=$(AS_TOKEN=$STRAT api POST /api/v1/copilot/changes "$BODY")
printf '%s' "$CS" | jq_ '
print("    changeset", d.get("id"), "| status", d.get("status"), "| model", (d.get("meta") or {}).get("model"), "| repair rounds", (d.get("meta") or {}).get("repairRound"))
print("    summary:", (d.get("summary") or "")[:400])
for a in d.get("assumptions") or []: print("    assumption:", a[:160])
for q in d.get("questions") or []: print("    question:", q[:160])
for c in d.get("changes") or []:
    v=c.get("validation") or {}
    print("    ·", c.get("kind").ljust(18), c.get("target").ljust(28), "tier", c.get("riskTier"), "| approvals", c.get("requiresApprovals"), "| valid", v.get("ok"), "| tests", len(c.get("tests") or []), ("| repaired" if c.get("repaired") else ""))
    print("        ", (c.get("rationale") or "")[:200])'
say "elapsed $(( $(date +%s) - START ))s"
ID=$(printf '%s' "$CS" | j 'd.get("id")')

step "3) The diff the reviewer sees for the first change (unified)"
printf '%s' "$CS" | jq_ 'c=(d.get("changes") or [None])[0]; print("\n".join("    "+l for l in (c.get("diff") or "").splitlines()[:40])) if c else print("    (no changes)")'

step "4) Stage every valid change as DRAFTS — decisions become studio drafts, state models become draft artefacts (the copilot never publishes)"
AS_TOKEN=$STRAT api POST "/api/v1/copilot/changes/$ID/stage" '{"changeIds":[]}' | jq_ '
print("    status", d.get("status"))
for c in d.get("changes") or []:
    s=c.get("staged") or {}; print("    ·", c.get("kind").ljust(18), c.get("target").ljust(28), "→", s.get("kind"), s.get("ref"), (c.get("stageError") or ""))'
api GET "/api/v1/copilot/artifacts?status=draft" | jq_ 'items=d if isinstance(d,list) else d.get("items", []); print("    draft artefacts awaiting a human apply:", [(a.get("kind"), a.get("target")) for a in items][:6])'

step "5) The decision drafts are now in the studio with the normal gates (lint → golden set → submit → approve → publish)"
for name in return_risk address_risk; do api GET "/api/v1/decisions/$name" | jq_ "print('    $name versions:', [(v.get('definition',v)['version'], v.get('definition',v)['status']) for v in d])"; done
DRAFT_V=$(api GET "/api/v1/decisions/return_risk" | j 'next((v.get("definition",v)["version"] for v in d if v.get("definition",v).get("status")=="draft"), "")')
if [ -n "$DRAFT_V" ]; then
  AS_TOKEN=$STRAT api POST "/api/v1/decisions/return_risk/versions/$DRAFT_V/lint" '{}' | jq_ 'items=d if isinstance(d,list) else d.get("issues",[]); print("    lint on the copilot draft:", len(items), "finding(s)", [ (i.get("severity"), i.get("code")) for i in items][:5])'
  EVAL=$(AS_TOKEN=$STRAT api POST "/api/v1/decisions/return_risk/versions/$DRAFT_V/evaluate" '{}')
  printf '%s' "$EVAL" | jq_ 'print("    golden set gate:", "passed" if d.get("passed") else "FAILED", "| accuracy", round(d.get("accuracy") or d.get("accuracyInActBand") or 0,3), "| cases", d.get("cases")); [print("      failing case:", f.get("caseId"), "expected", f.get("expected"), "got", f.get("actual")) for f in (d.get("failures") or [])[:4]]'
  if [ "$(printf '%s' "$EVAL" | j 'd.get("passed")')" != "True" ]; then
    step "5b) The gate did its job: the new gold rule outranks the policy denials (out of window, final sale). The reviewer lowers its priority in the studio and re-runs the gate"
    YAML=$(api GET "/api/v1/decisions/return_risk/versions/$DRAFT_V" | j 'd.get("definition",d)["yaml"]')
    FIXED=$(printf '%s' "$YAML" | python3 -c "
import sys,re; y=sys.stdin.read()
# move the copilot's gold rule behind the deny rules: any priority < 30 on a rule whose id mentions gold → 35
y=re.sub(r'(- id: gold[^\n]*\n(?:(?!- id:)[^\n]*\n)*?\s+priority: )\d+', r'\g<1>35', y)
print(y)")
    AS_TOKEN=$STRAT api POST "/api/v1/decisions/return_risk/versions/$DRAFT_V/yaml" "$(printf '%s' "$FIXED" | python3 -c 'import json,sys; print(json.dumps({"yaml": sys.stdin.read()}))')" >/dev/null
    AS_TOKEN=$STRAT api POST "/api/v1/decisions/return_risk/versions/$DRAFT_V/evaluate" '{}' | jq_ 'print("    golden set gate after the fix:", "passed" if d.get("passed") else "FAILED", "| accuracy", round(d.get("accuracy") or d.get("accuracyInActBand") or 0,3))'
    say "the draft now goes submit → approve (two approvers, T2) → canary → live, exactly like UC5"
  fi
fi

step "6) Plain-English explanation of a change, for the approver"
CID=$(printf '%s' "$CS" | j '(d.get("changes") or [{}])[0].get("id","")')
[ -n "$CID" ] && api GET "/api/v1/copilot/changes/$ID/changes/$CID/explain" | jq_ 'print("    "+(d.get("summary") or d.get("text") or "")[:500]); [print("    impact:", i[:160]) for i in (d.get("impacts") or [])[:3]]; [print("    risk:", r[:160]) for r in (d.get("risks") or [])[:3]]'

echo; say "Open $BASE/copilot (the ChangeSet with diffs) and $BASE/studio/return_risk (the draft). Talk track: the business owns the decision; the copilot writes the YAML, the validators and golden sets keep it honest, and nothing goes live without a person."
