#!/usr/bin/env bash
# The recording session (ADR 0006): the one place live model calls are made for the fixtures.
#
#   1. live check: two tiny calls prove the key, the auth header and the request shape (under one cent)
#   2. record: the golden set through the eval profile with LLM_ADAPTER=recording, under a spend cap
#   3. replay: the same run from the new fixtures only; every case must be answered, with no live call
#   4. grade the replay and write the baseline
#
# Needs the compose stack up (ledger-mcp, policy-mcp), ANTHROPIC_API_KEY in the environment or in the repository's
# git-ignored .env, and a spend limit set in the Anthropic Console. The key is never printed.
#
#   evals/record.sh                      # cap $10
#   RECORD_CAP_USD=5 evals/record.sh
set -euo pipefail
cd "$(dirname "$0")/.."
root=$(pwd)

fail() { echo "record: $*" >&2; exit 1; }

if [[ -z "${ANTHROPIC_API_KEY:-}" && -f .env ]]; then
    ANTHROPIC_API_KEY=$(grep -E '^ANTHROPIC_API_KEY=' .env | head -1 | cut -d= -f2- | tr -d '\r"' || true)
fi
[[ -n "${ANTHROPIC_API_KEY:-}" ]] || fail "ANTHROPIC_API_KEY is not set (environment or .env)"
export ANTHROPIC_API_KEY

cap_usd=${RECORD_CAP_USD:-10}
cap_micro=$(python -c "print(int(float('$cap_usd') * 1_000_000))")

curl -fsS -o /dev/null http://localhost:8081/actuator/health/readiness || fail "ledger-mcp is not up (compose)"
curl -fsS -o /dev/null http://localhost:8082/health || fail "policy-mcp is not up (compose)"
[[ -f data/generated/disputes.csv ]] || fail "data/generated is missing (uv run python generate.py in data/)"

cd investigator
echo "== 1. live check"
ANTHROPIC_LIVE_TEST=1 ./mvnw -B -q -ntp -pl adapters test -Dtest=AnthropicLiveTest -Dsurefire.failIfNoSpecifiedTests=false \
    || fail "the live check failed: nothing was recorded"
./mvnw -B -q -ntp -pl bootstrap -am package -DskipTests
jar=$(ls bootstrap/target/investigator-bootstrap-*.jar | grep -v original | head -1)

echo "== 2. record (cap \$$cap_usd)"
find ../evals/fixtures/llm -name '*.json' -delete
rm -rf ../evals/runs/recording ../evals/runs/replay
LLM_ADAPTER=recording EVAL_OUT=../evals/runs/recording EVAL_MAX_COST_MICRO_USD="$cap_micro" \
    java -jar "$jar" --spring.profiles.active=eval
python - <<'PY'
import json
run = json.load(open("../evals/runs/recording/run.json"))
print(f"recorded {run['cases_run']} of {run['cases']} cases for ${run['cost_micro_usd'] / 1e6:.4f}: {run['states']}")
if run["cases_run"] < run["cases"]:
    raise SystemExit("record: the spend cap stopped the run; fixtures are incomplete")
PY
echo "fixtures: $(find ../evals/fixtures/llm -name '*.json' | wc -l)"

echo "== 3. replay"
unset ANTHROPIC_API_KEY
LLM_ADAPTER=replay EVAL_OUT=../evals/runs/replay java -jar "$jar" --spring.profiles.active=eval

echo "== 4. grade and baseline"
cd "$root/evals"
uv run python -m evaltools.grade --results runs/replay --write-baseline
uv run python -m evaltools.grade --results runs/replay
