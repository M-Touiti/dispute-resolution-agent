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

# MCP servers: initialize (protocol 2025-11-25, as the investigator's Java client), then tools/list.
check_mcp() {
    local name=$1 port=$2 expected=$3
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
}

check_mcp ledger-mcp 8081 "get_authorisations get_card get_cardholder get_merchant get_transaction search_transactions"
check_mcp policy-mcp 8082 "get_policy_section search_policy"
check_mcp actions-mcp 8083 "close_dispute issue_provisional_credit request_merchant_evidence"

echo "smoke: all checks passed"
