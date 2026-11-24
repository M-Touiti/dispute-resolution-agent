#!/usr/bin/env bash
# Failure injection under load (task 2.14): one investigator, k6 at a steady rate through the shim, and one fault in
# the middle of the run. Writes the run's numbers, cut before, during and after the fault, to
# performance/results/fault-<scenario>.json, and fails if a decision was lost.
#
#   bash performance/faults.sh llm-overloaded   # the replay adapter answers "overloaded" (FAULT_FOR seconds)
#   bash performance/faults.sh policy-stall     # policy-mcp paused: its calls hang until the MCP request timeout
#   bash performance/faults.sh malformed        # MALFORMED events that are not JSON, published among the disputes
#
# RATE disputes per second (default 1), DURATION of the arrivals (default 150s), FAULT_AT seconds into the run
# (default 30), FAULT_FOR seconds (default 40), MALFORMED (default 5). Spends nothing: the replay adapter only.
set -euo pipefail

scenario=${1:-}
case "$scenario" in
    llm-overloaded | policy-stall | malformed) ;;
    *) echo "usage: $0 llm-overloaded|policy-stall|malformed" >&2; exit 2 ;;
esac
rate=${RATE:-1}
duration=${DURATION:-150s}
fault_at=${FAULT_AT:-30}
fault_for=${FAULT_FOR:-40}
malformed=${MALFORMED:-5}
root=$(cd "$(dirname "$0")/.." && pwd)
results="$root/performance/results"
cd "$root/deploy/compose"

fail() { echo "FAIL: $*" >&2; exit 1; }
[[ "${LLM_ADAPTER:-replay}" == "replay" ]] || fail "failure injection runs on the replay adapter only (LLM_ADAPTER=$LLM_ADAPTER)"
PY=$(command -v python || command -v python3) || fail "python is needed to read JSON answers"
export LLM_ADAPTER=replay
# A parked case waits for its replay: 30 s, then 60 s, ... A request may wait that long for its decision.
export SHIM_DECIDED_TIMEOUT=600
wait_for=600s

# MSYS_NO_PATHCONV: Git Bash on Windows would rewrite /scripts/... into a Windows path.
compose() { MSYS_NO_PATHCONV=1 docker compose --progress quiet --profile perf "$@"; }
now() { "$PY" -c 'import time; print(f"{time.time():.3f}")'; }

restore() {
    # Whatever happened: policy-mcp running, the switch off and the investigator back to its normal settings.
    compose unpause policy-mcp >/dev/null 2>&1 || true
    FAULT_INJECTION=false compose up -d --wait investigator >/dev/null 2>&1 || true
}
trap restore EXIT

echo "faults: $scenario, $rate disputes/s for $duration, fault at ${fault_at}s for ${fault_for}s"
FAULT_INJECTION=true compose up -d --build --wait --wait-timeout 600 --scale investigator=1 investigator load-shim
[[ "$(curl -fsS http://localhost:8080/faults)" == *llm_overloaded_until* ]] || fail "the fault switch is not on"

# --no-deps: run would otherwise reconcile the stack; FAULT_INJECTION stays as set above.
k6() { FAULT_INJECTION=true compose run --rm --no-deps -e "RATE=$1" -e "DURATION=$2" -e "TIMEOUT=$wait_for" k6 \
    run --quiet "${@:3}" /scripts/disputes.js; }
k6 1 10s --no-summary >/dev/null || fail "warm-up failed"
sleep 5

dlq_before=$(curl -fsS http://localhost:8090/dlq | "$PY" -c 'import json, sys; print(json.load(sys.stdin)["records"])')
start=$(now)
mkdir -p "$results"
k6 "$rate" "$duration" >"$results/fault-$scenario.k6.log" 2>&1 &
k6_pid=$!

sleep "$fault_at"
fault_start=$(now)
case "$scenario" in
    llm-overloaded)
        curl -fsS -X POST "http://localhost:8080/faults/llm-overloaded?seconds=$fault_for" >/dev/null
        echo "faults: model overloaded for ${fault_for}s"
        sleep "$fault_for"
        curl -fsS -X DELETE http://localhost:8080/faults/llm-overloaded >/dev/null ;;
    policy-stall)
        compose pause policy-mcp
        echo "faults: policy-mcp paused for ${fault_for}s"
        sleep "$fault_for"
        compose unpause policy-mcp ;;
    malformed)
        curl -fsS -X POST "http://localhost:8090/faults/malformed?count=$malformed" >/dev/null
        echo "faults: $malformed malformed events published"
        sleep "$fault_for" ;;
esac
fault_end=$(now)
echo "faults: fault over; waiting for k6 (requests wait up to $wait_for for their decision)"

k6_exit=0
wait "$k6_pid" || k6_exit=$?
end=$(now)
sleep 12  # one more scrape and lag sample after the last decision

mkdir -p "$results"
out="$results/fault-$scenario.json"
curl -fsS "http://localhost:8090/summary?since=$start&until=$end&fault_start=$fault_start&fault_end=$fault_end" \
    | "$PY" -c "
import json, sys
s = json.load(sys.stdin)
s['run'] = {'scenario': '$scenario', 'offered_per_second': $rate, 'duration': '$duration', 'fault_at_s': $fault_at,
            'fault_for_s': $fault_for, 'k6_exit': $k6_exit, 'adapter': 'replay', 'dlq_records_before': $dlq_before}
if '$scenario' == 'malformed':
    s['run']['malformed_published'] = $malformed
json.dump(s, open(sys.argv[1], 'w', encoding='utf-8'), indent=2)
print(json.dumps(s, indent=2))" "$out"
echo "faults: written $out"

read -r not_decided dlq_now <<<"$("$PY" -c "
import json, sys
s = json.load(open(sys.argv[1], encoding='utf-8'))
print(s['not_decided'], s['dlq_records_now'])" "$out")"
[[ "$not_decided" -eq 0 ]] || fail "$not_decided disputes were never decided"
[[ "$k6_exit" -eq 0 ]] || fail "k6 thresholds failed (exit $k6_exit; see $results/fault-$scenario.k6.log)"
if [[ "$scenario" == "malformed" ]]; then
    [[ $((dlq_now - dlq_before)) -eq "$malformed" ]] \
        || fail "dead letters: expected $malformed new records, found $((dlq_now - dlq_before))"
    echo "ok   all $malformed malformed events reached disputes.dlq"
fi
echo "ok   no decision lost; the stack is restored (switch off). Reset with docker compose down -v before smoke.sh"
