#!/usr/bin/env bash
# Checks the local overlay on kind (after deploy/k8s/kind/up.sh): every deployment available, the approval keys split
# between the two secrets, the network policies holding, the HPA reading CPU, the reviewer API answering, and one
# dispute through Kafka to its decision. Needs kubectl; the kind-dispute-agent context.
set -euo pipefail

namespace=dispute-agent
# A golden case decided with an action (approved below), as in deploy/compose/smoke.sh.
dispute=${CHECK_DISPUTE:-DSP-000018}
fail() { echo "FAIL: $*" >&2; exit 1; }
# MSYS_NO_PATHCONV: Git Bash on Windows would rewrite the container paths (/opt/kafka/...) into Windows paths.
k() { MSYS_NO_PATHCONV=1 kubectl --context "kind-${KIND_CLUSTER:-dispute-agent}" -n "$namespace" "$@"; }

for deployment in postgres kafka ledger-mcp policy-mcp actions-mcp investigator; do
    k rollout status "deployment/$deployment" --timeout=30s >/dev/null || fail "$deployment is not available"
done
echo "ok   deployments  postgres kafka ledger-mcp policy-mcp actions-mcp investigator available"

rows=$(k exec deploy/postgres -- sh -c 'psql -tA -U "$POSTGRES_USER" -d disputes -c "SELECT (SELECT count(*) FROM ledger.transactions) || chr(32) || (SELECT count(*) FROM intake.disputes)"')
read -r transactions disputes <<<"$rows"
[[ "$transactions" -gt 0 && "$disputes" -gt 0 ]] || fail "postgres: seed not loaded ('$rows')"
echo "ok   postgres     seed loaded: transactions=$transactions disputes=$disputes"

# ADR 0005: the investigator signs with the pair; actions-mcp holds and accepts the public key only.
verify_keys=$(k get secret approval-verify -o go-template='{{range $key, $_ := .data}}{{$key}} {{end}}')
[[ "$verify_keys" == "actions-mcp.approval.verify-keys " ]] \
    || fail "approval-verify holds '$verify_keys', expected the public key only"
signing=$(k logs deploy/investigator | grep -oE 'approval tokens are signed with Ed25519 key [0-9a-f]{16}' | tail -1 \
    | grep -oE '[0-9a-f]{16}$' || true)
accepted=$(k logs deploy/actions-mcp | grep -oE 'approval tokens are accepted from Ed25519 keys \[[0-9a-f, ]+\]' \
    | tail -1 | grep -oE '\[.*\]' || true)
[[ -n "$signing" && "$accepted" == "[$signing]" ]] \
    || fail "approval keys: investigator signs with '${signing:-none}', actions-mcp accepts '${accepted:-none}'"
echo "ok   approval     investigator signs with $signing; actions-mcp holds and accepts that public key only"

# Network policies: the write server and the ledger answer the investigator only; their actuator port stays open.
code() { k exec "deploy/$1" -- curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$2" 2>/dev/null || true; }
[[ "$(code investigator http://actions-mcp:8083/mcp)" != "000" ]] || fail "investigator cannot reach actions-mcp"
[[ "$(code investigator http://ledger-mcp:8081/mcp)" != "000" ]] || fail "investigator cannot reach ledger-mcp"
[[ "$(code ledger-mcp http://actions-mcp:8083/mcp)" == "000" ]] \
    || fail "ledger-mcp reaches actions-mcp:8083: the network policy is not enforced"
[[ "$(code actions-mcp http://ledger-mcp:8081/mcp)" == "000" ]] \
    || fail "actions-mcp reaches ledger-mcp:8081: the network policy is not enforced"
[[ "$(code ledger-mcp http://actions-mcp:9080/actuator/health/readiness)" == "200" ]] \
    || fail "actions-mcp's actuator port is not reachable"
echo "ok   network      actions-mcp:8083 and ledger-mcp:8081 refuse other pods; actuator ports open"

[[ "$(code investigator http://localhost:8080/reviews)" == "401" ]] || fail "reviewer API without a key is not 401"
status=$(k exec deploy/investigator -- curl -s -o /dev/null -w '%{http_code}' -H 'X-Reviewer-Key: local-reviewer-key' \
    http://localhost:8080/reviews || true)
[[ "$status" == "200" ]] || fail "reviewer API with the key answered $status"
echo "ok   reviewer     GET /reviews: 401 without the key, 200 with it"

for _ in $(seq 1 24); do
    cpu=$(k get hpa investigator -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}')
    [[ -n "$cpu" ]] && break
    sleep 5
done
[[ -n "$cpu" ]] || fail "the HPA reads no CPU metric (metrics-server)"
echo "ok   hpa          investigator at ${cpu}% of its CPU request, 1 to 3 replicas"

# One dispute end to end, as deploy/compose/smoke.sh does it: the event built from the seeded intake tables.
event=$(k exec -i deploy/postgres -- sh -c 'psql -tA -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d disputes' <<SQL
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
[[ "$event" == "{"* ]] || fail "no dispute $dispute in intake.disputes"
printf '%s@@%s\n' "$dispute" "$event" | k exec -i deploy/kafka -- sh -c '/opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic disputes.opened --reader-property parse.key=true \
    --reader-property key.separator=@@' || fail "could not publish disputes.opened"
decided=""
for _ in $(seq 1 24); do
    decided=$(k exec deploy/kafka -- sh -c '/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
        --topic disputes.decided --from-beginning --timeout-ms 5000 --formatter-property print.key=true \
        --formatter-property key.separator=@@ 2>/dev/null' | grep "^$dispute@@" | tail -1 || true)
    [[ -n "$decided" ]] && break
done
[[ -n "$decided" ]] || fail "no disputes.decided for $dispute within two minutes"
state=$(grep -oE '"state":"[A-Z_]+"' <<<"$decided" | cut -d'"' -f4 || true)
[[ -n "$state" ]] || fail "disputes.decided for $dispute carries no state: $decided"
echo "ok   dispute      $dispute through Kafka to disputes.decided (state $state)"

# The approval gate across the network policy (ADR 0005): the reviewer approves, the investigator signs a token and
# calls actions-mcp, which verifies it with the public key and records the write once.
sql() { k exec deploy/postgres -- sh -c "psql -tA -U \"\$POSTGRES_USER\" -d disputes -c \"$1\""; }
stored=$(sql "SELECT state FROM investigation.cases WHERE dispute_id = '$dispute'")
if [[ "$stored" == "AWAITING_APPROVAL" ]]; then
    approved=$(k exec deploy/investigator -- curl -sS -w '
%{http_code}' -X POST         "http://localhost:8080/reviews/$dispute/approve" -H 'X-Reviewer-Key: local-reviewer-key'         -H 'Content-Type: application/json' -d '{"reviewer":"kind-check","note":"kind check approval"}')
    [[ "$(tail -1 <<<"$approved")" == "200" && "$approved" == *'"state":"ACTED"'* ]]         || fail "approving $dispute answered $approved"
    tool=$(sql "SELECT tool FROM actions.actions WHERE dispute_id = '$dispute'")
    [[ -n "$tool" ]] || fail "actions-mcp recorded no action for $dispute"
    echo "ok   approval     $dispute approved; actions-mcp verified the token and recorded $tool"
elif [[ "$stored" == "ACTED" ]]; then
    echo "ok   approval     $dispute was approved on an earlier run (ACTED)"
else
    echo "ok   approval     $dispute is $stored: nothing to approve"
fi
echo "kind: all checks passed"
