#!/usr/bin/env bash
# Start infra and run the service locally with hot config reload from ./config
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
# Reference tenants seeded alongside the demo (folders under config/tenants; missing folders are skipped with a warning).
export OMS_DEMO_TENANTS=${OMS_DEMO_TENANTS:-demo,retail,electronics,apparel}
docker compose up -d postgres redis
cd backend && mvn -q -DskipTests install && cd oms-app && exec mvn -q spring-boot:run
