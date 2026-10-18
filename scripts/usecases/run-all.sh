#!/usr/bin/env bash
# Runs the demo use cases in sequence against a running Intent OMS (BASE / TENANT as in lib.sh).
cd "$(dirname "$0")"
for s in uc1-intent-sourcing.sh uc2-returns-margin-recovery.sh uc3-disruption.sh uc4-agent-orders.sh uc5-policy-change.sh uc6-prompt-configured.sh uc7-margin-guardian.sh uc8-self-optimizing.sh; do ./"$s" || { echo "FAILED: $s"; exit 1; }; echo; done
