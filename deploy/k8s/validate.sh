#!/usr/bin/env bash
# Validates every manifest under deploy/k8s without a cluster (CI runs this; task 2.15): each Kustomize build, then
# kubeconform in strict mode against the Kubernetes version of the kind node, with the ServiceMonitor and Argo CD
# schemas from the CRDs catalog. Needs kubectl (its built-in Kustomize) and kubeconform.
set -euo pipefail
cd "$(dirname "$0")"

k8s_version=${K8S_VERSION:-1.37.0}
fail() { echo "FAIL: $*" >&2; exit 1; }
command -v kubectl >/dev/null || fail "kubectl is needed"
command -v kubeconform >/dev/null || fail "kubeconform is needed"

validate() {
    kubeconform -strict -summary -kubernetes-version "$k8s_version" \
        -schema-location default \
        -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
        "$@"
}

for dir in base overlays/local monitoring; do
    echo "== $dir"
    kubectl kustomize "$dir" | validate - || fail "$dir does not validate"
done
echo "== argocd"
validate argocd/application.yaml || fail "argocd/application.yaml does not validate"

# The data stores use the compose stack's images, which Dependabot bumps there: the two must not drift apart.
echo "== images"
for image in pgvector/pgvector apache/kafka; do
    compose=$(grep -oE "image: $image:[^ ]+" ../compose/docker-compose.yml | head -1)
    local_=$(grep -ohE "image: $image:[^ ]+" overlays/local/*.yaml | head -1)
    [[ -n "$compose" && "$compose" == "$local_" ]] \
        || fail "overlays/local has '${local_:-none}', compose has '${compose:-none}': keep them equal"
    echo "${compose#image: } as in compose"
done
