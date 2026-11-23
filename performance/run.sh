#!/usr/bin/env bash
# One load-test run (task 2.13): the compose stack with N investigator replicas on the replay adapter, a warm-up,
# then k6 at a constant arrival rate, then the run's numbers from the shim into performance/results/.
#
#   REPLICAS=3 RATE=6 DURATION=60s bash performance/run.sh
#
# REPLICAS (default 1), RATE disputes per second (default 2), DURATION (default 60s), WARMUP seconds at 1 per second
# (default 20, not counted). Needs data/generated and Docker; spends nothing (LLM_ADAPTER stays replay).
set -euo pipefail

replicas=${REPLICAS:-1}
rate=${RATE:-2}
duration=${DURATION:-60s}
warmup=${WARMUP:-20}
root=$(cd "$(dirname "$0")/.." && pwd)
results="$root/performance/results"
cd "$root/deploy/compose"

fail() { echo "FAIL: $*" >&2; exit 1; }
[[ "${LLM_ADAPTER:-replay}" == "replay" ]] || fail "the load test runs on the replay adapter only (LLM_ADAPTER=$LLM_ADAPTER)"
export LLM_ADAPTER=replay
PY=$(command -v python || command -v python3) || fail "python is needed to read JSON answers"

files=(-f docker-compose.yml)
# Several replicas cannot share the investigator's host port.
[[ "$replicas" -gt 1 ]] && files+=(-f ../../performance/compose.replicas.yml)
# MSYS_NO_PATHCONV: Git Bash on Windows would rewrite /scripts/... into a Windows path.
compose() { MSYS_NO_PATHCONV=1 docker compose --progress quiet "${files[@]}" --profile perf "$@"; }

echo "run: $replicas investigator replica(s), $rate disputes/s for $duration (warm-up ${warmup}s)"
compose up -d --build --wait --wait-timeout 600 --scale "investigator=$replicas" investigator load-shim

# --no-deps: run would otherwise reconcile the investigator back to one replica.
k6() { compose run --rm --no-deps -e "RATE=$1" -e "DURATION=$2" k6 run --quiet "${@:3}" /scripts/disputes.js; }

if [[ "$warmup" -gt 0 ]]; then
    k6 1 "${warmup}s" --no-summary >/dev/null || fail "warm-up failed"
fi
# Let Prometheus see every replica before the window opens.
sleep 10

start=$(date +%s)
k6_exit=0
k6 "$rate" "$duration" || k6_exit=$?
end=$(date +%s)
running=$(compose ps --status running --format '{{.Service}}' | grep -c '^investigator$' || true)
sleep 12  # one more scrape after the last decision

mkdir -p "$results"
out="$results/replicas-$replicas-rate-$rate.json"
curl -fsS "http://localhost:8090/summary?since=$start&until=$end" | "$PY" -c "
import json, sys
s = json.load(sys.stdin)
s['run'] = {'replicas': $replicas, 'replicas_running_at_end': $running, 'offered_per_second': $rate, 'duration': '$duration', 'k6_exit': $k6_exit,
            'adapter': 'replay'}
json.dump(s, open(sys.argv[1], 'w', encoding='utf-8'), indent=2)
print(json.dumps(s, indent=2))" "$out"
echo "run: written $out"
echo "run: the golden cases were re-run; reset the stack (docker compose down -v) before smoke.sh"
[[ "$k6_exit" -eq 0 ]] || fail "k6 thresholds failed (exit $k6_exit): a decision was lost or timed out"
