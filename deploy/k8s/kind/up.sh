#!/usr/bin/env bash
# The local overlay on a kind cluster (task 2.15), from the repository root or anywhere:
#   bash deploy/k8s/kind/up.sh       # create (or reuse) the cluster, build and load the images, apply, wait
#   bash deploy/k8s/kind/check.sh    # what runs: probes, secrets, network policies, HPA, one dispute end to end
#   kind delete cluster --name dispute-agent
# Needs docker, kind and kubectl, and data/generated (cd data && uv run python generate.py). Replay only: no key, no
# spend. The repository root is mounted into the node at /repo; the overlay reads the seed, the init scripts, the
# skills, the fixtures and the policy corpus from there.
set -euo pipefail

cluster=${KIND_CLUSTER:-dispute-agent}
# kind v0.33.0's default node image, pinned by digest (from the release notes).
node_image=${KIND_NODE_IMAGE:-kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5}
metrics_server=v0.9.1
namespace=dispute-agent
root=$(cd "$(dirname "$0")/../../.." && pwd)

fail() { echo "FAIL: $*" >&2; exit 1; }
for tool in docker kind kubectl; do command -v "$tool" >/dev/null || fail "$tool is needed"; done
[[ -f "$root/data/generated/seed.sql" ]] || fail "no data/generated/seed.sql: run 'uv run python generate.py' in data/"

# kind on Windows wants a Windows path for the mount (Git Bash's /c/... is not one).
host_root=$root
command -v cygpath >/dev/null && host_root=$(cygpath -m "$root")

if kind get clusters 2>/dev/null | grep -qx "$cluster"; then
    echo "kind: cluster $cluster exists; reusing it"
else
    kind create cluster --name "$cluster" --image "$node_image" --wait 120s --config - <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraMounts:
      - hostPath: $host_root
        containerPath: /repo
        readOnly: true
EOF
fi
kubectl config use-context "kind-$cluster" >/dev/null

# The same images as the compose stack, built by compose and copied into the node (nothing is pulled from a registry
# for them; imagePullPolicy is IfNotPresent).
images=(investigator ledger-mcp policy-mcp actions-mcp)
docker compose -f "$root/deploy/compose/docker-compose.yml" build "${images[@]}"
for image in "${images[@]}"; do
    kind load docker-image --name "$cluster" "dispute-agent/$image:dev"
done

# metrics-server for the investigator's HPA; kind's kubelets serve self-signed certificates.
kubectl apply -f "https://github.com/kubernetes-sigs/metrics-server/releases/download/$metrics_server/components.yaml" >/dev/null
kubectl -n kube-system patch deployment metrics-server --type json \
    -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' >/dev/null 2>&1 \
    || true  # already patched on a reused cluster
echo "kind: metrics-server $metrics_server"

kubectl create namespace "$namespace" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# The approval-token key pair (ADR 0005), once per cluster, by the compose stack's generator (JDK only) in a
# container: the investigator's secret holds the private and public key, actions-mcp's the public key only. The keys
# pass through a temporary directory that is removed on exit; nothing is committed or kept.
if ! kubectl -n "$namespace" get secret approval-signing >/dev/null 2>&1; then
    keys=$(mktemp -d)
    trap 'rm -rf "$keys"' EXIT
    docker run --rm -i eclipse-temurin:21-jdk-noble sh -c \
        'cat > /tmp/GenerateApprovalKeys.java && java /tmp/GenerateApprovalKeys.java /keys/signing /keys/verify >&2 \
         && tar -C /keys -cf - signing verify' \
        <"$root/deploy/compose/approval-keys/GenerateApprovalKeys.java" | tar -xf - -C "$keys"
    kubectl -n "$namespace" create secret generic approval-signing --from-file="$keys/signing" >/dev/null
    kubectl -n "$namespace" create secret generic approval-verify --from-file="$keys/verify" >/dev/null
    rm -rf "$keys"
    echo "kind: approval keys generated into secrets approval-signing and approval-verify"
else
    echo "kind: approval keys exist; kept"
fi

kubectl apply -k "$root/deploy/k8s/overlays/local"
for deployment in postgres kafka ledger-mcp policy-mcp actions-mcp investigator; do
    kubectl -n "$namespace" rollout status "deployment/$deployment" --timeout=600s
done
kubectl -n kube-system rollout status deployment/metrics-server --timeout=180s
echo "kind: the stack is up in namespace $namespace; next: bash deploy/k8s/kind/check.sh"
