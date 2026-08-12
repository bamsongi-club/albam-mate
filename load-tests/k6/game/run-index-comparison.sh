#!/usr/bin/env bash
set -euo pipefail

index_state="${INDEX_STATE:-}"
profile="${PROFILE:-load}"
script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "$script_directory/../../.." && pwd)"
result_directory="${RESULT_DIR:-$repository_root/build/k6/game}"
base_url="${BASE_URL:-http://localhost:8080}"

case "$index_state" in
  no-pg-trgm|pg-trgm-gin)
    ;;
  *)
    echo 'INDEX_STATE must be no-pg-trgm or pg-trgm-gin' >&2
    exit 2
    ;;
esac

for required_name in KEYWORD EXPECTED_TOTAL_ELEMENTS BENCHMARK_ID RELEASE_SHA FIXTURE_ID FIXTURE_SHA256; do
  if [[ -z "${!required_name:-}" ]]; then
    echo "$required_name is required" >&2
    exit 2
  fi
done

keyword="$KEYWORD"
expected_total_elements="$EXPECTED_TOTAL_ELEMENTS"
benchmark_id="$BENCHMARK_ID"
release_sha="$RELEASE_SHA"
fixture_id="$FIXTURE_ID"
fixture_sha256="$FIXTURE_SHA256"
soak_duration="${SOAK_DURATION:-1h}"
auth_mode=anonymous
if [[ -n "${SESSION_COOKIE:-}${JSESSIONID:-}" ]]; then
  auth_mode=authenticated
fi

if [[ ! "$benchmark_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo 'BENCHMARK_ID may contain only letters, numbers, dot, underscore, and hyphen' >&2
  exit 2
fi
if [[ ! "$release_sha" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo 'RELEASE_SHA must be a 40-character Git SHA' >&2
  exit 2
fi
if [[ ! "$fixture_id" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo 'FIXTURE_ID may contain only letters, numbers, dot, underscore, and hyphen' >&2
  exit 2
fi
if [[ ! "$fixture_sha256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo 'FIXTURE_SHA256 must be a 64-character SHA-256' >&2
  exit 2
fi
if [[ ! "$expected_total_elements" =~ ^[0-9]+$ ]]; then
  echo 'EXPECTED_TOTAL_ELEMENTS must be a non-negative integer' >&2
  exit 2
fi

mkdir -p "$result_directory"

scenario_sha256="$(
  shasum -a 256 \
    "$script_directory/common.js" \
    "$script_directory/02-game-keyword.js" \
    "$script_directory/08-game-realistic.js" |
    awk '{print $1}' |
    shasum -a 256 |
    awk '{print $1}'
)"
contract_sha256="$(shasum -a 256 "$script_directory/00-game-keyword-contract.js" | awk '{print $1}')"
k6_version="$(k6 version | head -n 1)"
manifest="$result_directory/${benchmark_id}.manifest.json"

node "$script_directory/index-comparison-manifest.mjs" prepare \
  "$manifest" \
  "$benchmark_id" \
  "$index_state" \
  "$release_sha" \
  "$fixture_id" \
  "$fixture_sha256" \
  "$scenario_sha256" \
  "$contract_sha256" \
  "$keyword" \
  "$expected_total_elements" \
  "$base_url" \
  "$profile" \
  "$soak_duration" \
  "$auth_mode" \
  "$k6_version"

contract_summary="$result_directory/${index_state}-${benchmark_id}-00-game-keyword-contract.summary.json"
contract_log="$result_directory/${index_state}-${benchmark_id}-00-game-keyword-contract.log"
k6 run \
  --summary-export "$contract_summary" \
  -e BASE_URL="$base_url" \
  -e KEYWORD="$keyword" \
  -e EXPECTED_TOTAL_ELEMENTS="$expected_total_elements" \
  "$script_directory/00-game-keyword-contract.js" 2>&1 | tee "$contract_log"

artifacts=("$contract_summary" "$contract_log")
overall_status=0

for scenario in 02-game-keyword.js 08-game-realistic.js; do
  name="${scenario%.js}"
  summary="$result_directory/${index_state}-${benchmark_id}-${name}-${profile}.summary.json"
  log="$result_directory/${index_state}-${benchmark_id}-${name}-${profile}.log"

  set +e
  k6 run \
    --tag benchmark=game-551 \
    --tag benchmark_id="$benchmark_id" \
    --tag index_state="$index_state" \
    --tag release_sha="$release_sha" \
    --tag fixture_id="$fixture_id" \
    --tag fixture_sha256="$fixture_sha256" \
    --tag scenario_sha256="$scenario_sha256" \
    --tag auth_mode="$auth_mode" \
    --summary-trend-stats 'avg,min,med,max,p(90),p(95),p(99)' \
    --summary-export "$summary" \
    -e BASE_URL="$base_url" \
    -e KEYWORD="$keyword" \
    -e PROFILE="$profile" \
    -e SOAK_DURATION="$soak_duration" \
    "$script_directory/$scenario" 2>&1 | tee "$log"
  scenario_status="${PIPESTATUS[0]}"
  set -e

  if [[ "$scenario_status" -ne 0 && "$overall_status" -eq 0 ]]; then
    overall_status="$scenario_status"
  fi

  artifacts+=("$summary" "$log")
done

node "$script_directory/index-comparison-manifest.mjs" record \
  "$manifest" \
  "$index_state" \
  "${artifacts[@]}"

exit "$overall_status"
