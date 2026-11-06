#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${1:-http://localhost:18080}"
BASE_URL="${BASE_URL%/}"
MODE="${2:-required}"
ATTEMPTS="${OPENIMAGE_SMOKE_ATTEMPTS:-30}"
SLEEP_SECONDS="${OPENIMAGE_SMOKE_SLEEP_SECONDS:-3}"

for attempt in $(seq 1 "$ATTEMPTS"); do
  if curl -fsS "$BASE_URL/api/public/ping"; then
    echo
    echo "Smoke OK: $BASE_URL"
    exit 0
  fi
  if [ "$attempt" -lt "$ATTEMPTS" ]; then
    sleep "$SLEEP_SECONDS"
  fi
done

if [ "$MODE" = "--optional" ]; then
  echo "Smoke target is not reachable; skipped optional smoke gate: $BASE_URL"
  exit 0
fi

echo "Smoke failed after $ATTEMPTS attempts: $BASE_URL"
exit 1
