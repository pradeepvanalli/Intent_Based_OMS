#!/usr/bin/env bash
# Builds the React UI and copies it into the Spring Boot static resources so oms-app serves it.
# Usage: scripts/build-ui.sh            (from anywhere)
#        VITE_API_BASE=https://api.example.com scripts/build-ui.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UI_DIR="$ROOT/ui"
STATIC_DIR="$ROOT/backend/oms-app/src/main/resources/static"

command -v npm >/dev/null 2>&1 || { echo "npm is required (Node 20+)" >&2; exit 1; }

echo "▸ Installing UI dependencies (npm ci)"
cd "$UI_DIR"
if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi

echo "▸ Building UI (tsc + vite build)"
npm run build

echo "▸ Copying dist/ → $STATIC_DIR"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -R "$UI_DIR/dist/." "$STATIC_DIR/"

echo "▸ Done. $(find "$STATIC_DIR" -type f | wc -l | tr -d ' ') files in static/."
echo "  Remember: the backend must serve index.html for unknown non-API routes (SPA fallback)."
