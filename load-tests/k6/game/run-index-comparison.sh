#!/usr/bin/env bash
set -euo pipefail

index_state="${INDEX_STATE:-}"
profile="${PROFILE:-load}"
script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "$script_directory/../../.." && pwd)"
result_directory="${RESULT_DIR:-$repository_root/build/k6/game}"
base_url="${BASE_URL:-http://localhost:8080}"
run_id="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"

case "$index_state" in
  no-pg-trgm|pg-trgm-gin)
    ;;
  *)
    echo 'INDEX_STATE must be no-pg-trgm or pg-trgm-gin' >&2
    exit 2
    ;;
esac

mkdir -p "$result_directory"

for scenario in 02-game-keyword.js 08-game-realistic.js; do
  name="${scenario%.js}"

  k6 run \
    --tag benchmark=game-551 \
    --tag index_state="$index_state" \
    --summary-trend-stats 'avg,min,med,max,p(90),p(95),p(99)' \
    --summary-export "$result_directory/${index_state}-${run_id}-${name}-${profile}.summary.json" \
    -e BASE_URL="$base_url" \
    -e PROFILE="$profile" \
    "$script_directory/$scenario"
done
