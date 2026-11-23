#!/usr/bin/env bash
# Checks a running stack (docker compose up -d --wait): the seed is loaded, Kafka answers, and each MCP server
# completes the initialize handshake and lists the expected tools. Needs bash, curl and docker.
set -euo pipefail
cd "$(dirname "$0")"

fail() { echo "FAIL: $*" >&2; exit 1; }

# PostgreSQL: pgvector is installed and the seed is loaded.
rows=$(docker compose exec -T postgres sh -c \
    'psql -tA -U "$POSTGRES_USER" -d disputes -c "SELECT (SELECT count(*) FROM ledger.transactions) || chr(32) || (SELECT count(*) FROM intake.disputes) || chr(32) || (SELECT extversion FROM pg_extension WHERE extname = '"'"'vector'"'"')"')
read -r transactions disputes vector <<<"$rows"
[[ "$transactions" -gt 0 && "$disputes" -gt 0 && -n "$vector" ]] || fail "postgres: '$rows'"
echo "ok   postgres     transactions=$transactions disputes=$disputes pgvector=$vector"

# Kafka: create, describe and delete a scratch topic on the internal listener. Commands run through sh -c so that
# Git Bash on Windows does not rewrite the container paths.
docker compose exec -T kafka sh -c '
    set -e
    bin=/opt/kafka/bin
    $bin/kafka-topics.sh --bootstrap-server kafka:19092 --create --if-not-exists --topic smoke.check \
        --partitions 1 --replication-factor 1 > /dev/null
    $bin/kafka-topics.sh --bootstrap-server kafka:19092 --describe --topic smoke.check | grep -q "Leader: 1"
    $bin/kafka-topics.sh --bootstrap-server kafka:19092 --delete --topic smoke.check' \
    || fail "kafka: could not create and describe a topic"
echo "ok   kafka        topic round trip on kafka:19092"

# MCP servers: initialize (protocol 2025-11-25, as the investigator's Java client), then tools/list. With a
# fourth and fifth argument, also call a tool (params JSON) and require a substring in the answer.
check_mcp() {
    local name=$1 port=$2 expected=$3 call=${4:-} want=${5:-}
    local url="http://localhost:$port/mcp"
    local accept='Accept: application/json, text/event-stream'
    local headers session tools

    headers=$(curl -fsS -D - -o /dev/null -X POST "$url" -H 'Content-Type: application/json' -H "$accept" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}') \
        || fail "$name: initialize failed"
    session=$(tr -d '\r' <<<"$headers" | awk 'tolower($1) == "mcp-session-id:" {print $2}')
    [[ -n "$session" ]] || fail "$name: no Mcp-Session-Id"

    curl -fsS -o /dev/null -X POST "$url" -H 'Content-Type: application/json' -H "$accept" \
        -H "Mcp-Session-Id: $session" -H 'MCP-Protocol-Version: 2025-11-25' \
        -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' || fail "$name: initialized failed"

    tools=$(curl -fsS -X POST "$url" -H 'Content-Type: application/json' -H "$accept" \
        -H "Mcp-Session-Id: $session" -H 'MCP-Protocol-Version: 2025-11-25' \
        -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
        | grep -oE '"name" ?: ?"[a-z_]+"' | sed -E 's/.*"([a-z_]+)"$/\1/' | sort | tr '\n' ' ' | sed 's/ $//') \
        || fail "$name: tools/list failed"
    [[ "$tools" == "$expected" ]] || fail "$name: tools are '$tools', expected '$expected'"
    echo "ok   $(printf '%-12s' "$name") $tools"

    if [[ -n "$call" ]]; then
        local answer
        answer=$(curl -fsS -X POST "$url" -H 'Content-Type: application/json' -H "$accept" \
            -H "Mcp-Session-Id: $session" -H 'MCP-Protocol-Version: 2025-11-25' \
            -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":$call}") \
            || fail "$name: tools/call failed"
        if grep -q '"isError":true' <<<"$answer"; then fail "$name: tool error: $answer"; fi
        grep -qF "$want" <<<"$answer" || fail "$name: answer lacks '$want': $answer"
        echo "ok   $(printf '%-12s' "$name") tools/call answered from its data"
    fi
}

# A real ledger read: the first card in the generated data, with its last four digits.
card=$(sed -n 2p ../../data/generated/cards.csv | cut -d, -f1)
last4=$(sed -n 2p ../../data/generated/cards.csv | cut -d, -f3)

# The tool's JSON answer is a string inside the JSON-RPC response, so its quotes arrive escaped.
check_mcp ledger-mcp 8081 "get_authorisations get_card get_cardholder get_merchant get_transaction search_transactions" \
    "{\"name\":\"get_card\",\"arguments\":{\"card_id\":\"$card\"}}" "pan_last4\\\":\\\"$last4"

# A real policy search over the ingested corpus: the time-limit section must be among the first three.
check_mcp policy-mcp 8082 "get_policy_section search_policy" \
    '{"name":"search_policy","arguments":{"query":"time limit for opening a dispute","max_results":3}}' \
    'time-limits'

check_mcp actions-mcp 8083 "close_dispute issue_provisional_credit request_merchant_evidence"

curl -fsS -o /dev/null http://localhost:8080/actuator/health/readiness || fail "investigator: not ready"
echo "ok   investigator ready"

# Approval-token keys (ADR 0005): generated once by approval-keys; the investigator signs with that pair, and the
# volume actions-mcp gets holds the public key only.
generated=$(docker compose logs --no-log-prefix approval-keys 2>/dev/null | grep -oE 'key id [0-9a-f]{16}' | tail -1)
signing=$(docker compose logs --no-log-prefix investigator 2>/dev/null \
    | grep -oE 'approval tokens are signed with Ed25519 key [0-9a-f]{16}' | tail -1 | grep -oE '[0-9a-f]{16}$')
[[ -n "$generated" && "$generated" == "key id $signing" ]] \
    || fail "approval keys: generated '${generated:-none}', investigator signs with '${signing:-none}'"
verify_files=$(docker compose run --rm --no-deps --entrypoint sh approval-keys -c 'ls /keys/verify' 2>/dev/null | tr -d '\r')
[[ "$verify_files" == "actions-mcp.approval.verify-keys" ]] \
    || fail "approval keys: the verify volume holds '$verify_files', expected the public key only"
echo "ok   approval keys: the investigator signs with $signing; actions-mcp's volume holds the public key only"
accepted=$(docker compose logs --no-log-prefix actions-mcp 2>/dev/null \
    | grep -oE 'approval tokens are accepted from Ed25519 keys \[[0-9a-f, ]+\]' | tail -1 | grep -oE '\[.*\]')
[[ "$accepted" == "[$signing]" ]] || fail "actions-mcp accepts keys '${accepted:-none}', expected [$signing]"
migrated=$(docker compose exec -T postgres sh -c "psql -tA -U \"\$POSTGRES_USER\" -d disputes -c \
    \"SELECT count(*) FROM actions.flyway_schema_history WHERE success\"")
[[ "$migrated" -ge 1 ]] || fail "actions-mcp: the actions schema is not migrated"
echo "ok   actions-mcp verifies tokens from key $signing only; actions schema migrated"

# One dispute through the investigator: the disputes.opened event is built inside PostgreSQL from the seeded intake
# tables (no CSV parsing in shell), published, and the matching disputes.decided awaited. Prints the decided event.
investigate() {
    local dispute=$1 event decided=""
    event=$(docker compose exec -T postgres sh -c 'psql -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d disputes' <<SQL
SELECT json_build_object(
    'version', 1, 'dispute_id', d.dispute_id, 'cardholder_id', d.cardholder_id, 'card_id', d.card_id,
    'transaction_id', d.transaction_id, 'category', d.category,
    'opened_at', to_char(d.opened_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    'claimed_amount_minor', d.claimed_amount_minor, 'currency', d.currency,
    'cardholder_statement', d.cardholder_statement,
    'documents', COALESCE((SELECT json_agg(json_build_object('document_id', x.document_id, 'kind', x.kind,
        'issued_by', x.issued_by, 'dated', to_char(x.dated, 'YYYY-MM-DD'), 'summary', x.summary)
        ORDER BY x.document_id) FROM intake.dispute_documents x WHERE x.dispute_id = d.dispute_id), '[]'::json))
FROM intake.disputes d WHERE d.dispute_id = '$dispute';
SQL
)
    [[ "$event" == "{"* ]] || fail "investigator: no dispute $dispute in intake.disputes"

    # "@@" separates key and value: it cannot occur in the generated statements.
    printf '%s@@%s\n' "$dispute" "$event" | docker compose exec -T kafka sh -c '/opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server kafka:19092 --topic disputes.opened --reader-property parse.key=true --reader-property key.separator=@@' \
        || fail "investigator: could not publish disputes.opened"

    for _ in $(seq 1 24); do
        decided=$(docker compose exec -T kafka sh -c '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 \
            --topic disputes.decided --from-beginning --timeout-ms 5000 --formatter-property print.key=true \
            --formatter-property key.separator=@@ 2>/dev/null' | grep "^$dispute@@" | tail -1 || true)
        [[ -n "$decided" ]] && break
    done
    [[ -n "$decided" ]] || fail "investigator: no disputes.decided for $dispute within two minutes"
    printf '%s' "$decided"
}

sql() {
    docker compose exec -T postgres sh -c "psql -tA -U \"\$POSTGRES_USER\" -d disputes -c \"$1\""
}

# The default is a golden case, replayed from the recorded fixtures, and must come out DECIDED: Kafka in, the evidence
# read from ledger-mcp, the model loop, the guard, the case and audit stored, Kafka out. Any other SMOKE_DISPUTE has
# no fixture unless it is in the golden set; it then escalates (DEPENDENCY_UNAVAILABLE), which still proves the
# pipeline.
dispute=${SMOKE_DISPUTE:-DSP-000018}
decided=$(investigate "$dispute")
state=$(grep -oE '"state":"[A-Z]+"' <<<"$decided" | cut -d'"' -f4)
reason=$(grep -oE '"escalation_reason":"[A-Z_]+"' <<<"$decided" | cut -d'"' -f4 || true)
IFS='|' read -r tool_rows audit_rows <<<"$(sql "SELECT count(*) FILTER (WHERE type = 'ToolCalled'), count(*) \
    FROM investigation.audit_events WHERE dispute_id = '$dispute'")"
[[ "$tool_rows" -gt 0 ]] || fail "investigator: no tool calls audited for $dispute"
if [[ -z "${SMOKE_DISPUTE:-}" && "$state" != "DECIDED" ]]; then
    fail "investigator: golden case $dispute ended $state${reason:+ ($reason)}, expected DECIDED (a fixture miss?)"
fi
echo "ok   investigator $dispute -> $state${reason:+ ($reason)}; $audit_rows audit rows, $tool_rows tool calls"

# A decided case's action waits for a person (ADR 0005): stored AWAITING_APPROVAL, one disputes.approval-requested.
# On a stack that already ran the smoke test the case is past that; it is reported, not re-reviewed.
[[ "$state" == "DECIDED" ]] || { echo "smoke: all checks passed"; exit 0; }
stored=$(sql "SELECT state FROM investigation.cases WHERE dispute_id = '$dispute'")
if [[ "$stored" != "AWAITING_APPROVAL" ]]; then
    echo "ok   investigator $dispute was reviewed on an earlier run (stored $stored)"
    echo "smoke: all checks passed"
    exit 0
fi
requested=$(docker compose exec -T kafka sh -c '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 \
    --topic disputes.approval-requested --from-beginning --timeout-ms 5000 --formatter-property print.key=true \
    --formatter-property key.separator=@@ 2>/dev/null' | grep "^$dispute@@" | tail -1 || true)
[[ -n "$requested" ]] || fail "investigator: no disputes.approval-requested for $dispute"
action=$(grep -oE '"action":\{"action":"[a-z_]+"' <<<"$requested" | cut -d'"' -f6)
echo "ok   investigator $dispute waits for approval: $action"

# The reviewer API (task 2.7): the queue, an approval carried out by actions-mcp once, a rejection that does nothing.
reviews=http://localhost:8080/reviews
key=${REVIEWER_API_KEY:-local-reviewer-key}
status=$(curl -s -o /dev/null -w '%{http_code}' "$reviews")
[[ "$status" == "401" ]] || fail "reviewer API: $status without a key, expected 401"
curl -fsS -H "X-Reviewer-Key: $key" "$reviews" | grep -qF "\"dispute_id\":\"$dispute\"" \
    || fail "reviewer API: $dispute is not in the queue"

approved=$(curl -sS -w '\n%{http_code}' -X POST "$reviews/$dispute/approve" -H "X-Reviewer-Key: $key" \
    -H 'Content-Type: application/json' -d '{"reviewer":"smoke","note":"smoke test approval"}')
[[ "$(tail -1 <<<"$approved")" == "200" && "$approved" == *'"state":"ACTED"'* ]] \
    || fail "reviewer API: approving $dispute answered $approved"
IFS='|' read -r tool amount <<<"$(sql "SELECT tool, amount_minor FROM actions.actions WHERE dispute_id = '$dispute'")"
claimed=$(sql "SELECT claimed_amount_minor FROM intake.disputes WHERE dispute_id = '$dispute'")
[[ "$tool" == "$action" ]] || fail "actions-mcp: recorded '$tool' for $dispute, expected $action"
[[ "$tool" != "issue_provisional_credit" || "$amount" == "$claimed" ]] \
    || fail "actions-mcp: credited $amount for $dispute, the decision was $claimed"
[[ "$(sql "SELECT state FROM investigation.cases WHERE dispute_id = '$dispute'")" == "ACTED" ]] \
    || fail "investigator: $dispute is not ACTED after the approval"
again=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$reviews/$dispute/approve" -H "X-Reviewer-Key: $key" \
    -H 'Content-Type: application/json' -d '{"reviewer":"smoke-2"}')
[[ "$again" == "409" ]] || fail "reviewer API: a second approval of $dispute answered $again, expected 409"
echo "ok   reviewer API: $dispute approved, actions-mcp verified the token and recorded $tool${amount:+ of $amount}; a second approval is 409"

# A second golden case, a decline, rejected by the reviewer: nothing is carried out.
second=DSP-000003
second_decided=$(investigate "$second")
[[ "$second_decided" == *'"state":"DECIDED"'* ]] || fail "investigator: golden case $second was not decided"
rejected=$(curl -sS -w '\n%{http_code}' -X POST "$reviews/$second/reject" -H "X-Reviewer-Key: $key" \
    -H 'Content-Type: application/json' -d '{"reviewer":"smoke","note":"smoke test rejection"}')
[[ "$(tail -1 <<<"$rejected")" == "200" && "$rejected" == *'"state":"REJECTED"'* ]] \
    || fail "reviewer API: rejecting $second answered $rejected"
[[ "$(sql "SELECT count(*) FROM actions.actions WHERE dispute_id = '$second'")" == "0" ]] \
    || fail "actions-mcp: an action was recorded for the rejected $second"
echo "ok   reviewer API: $second rejected; actions-mcp recorded nothing"

echo "smoke: all checks passed"
