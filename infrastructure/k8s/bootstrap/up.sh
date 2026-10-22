#!/bin/sh
# Creates the local demo cluster and installs everything the platform's
# manifests assume is already there: a NetworkPolicy-enforcing CNI, metrics-server
# (HPAs), CloudNativePG and the RabbitMQ cluster operator. Idempotent -- every
# step is a no-op when already done, so re-running after a failure is safe.
#
# What it deliberately does NOT do: build or deploy the platform. That is
# `make skaffold-run` / `make skaffold-dev` (Task 12.6), which applies
# infrastructure/k8s/overlays/local on top of this.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
. "${here}/versions.env"

CLUSTER=fondaria
CONTEXT="kind-${CLUSTER}"

for tool in kind kubectl linkerd; do
  command -v "${tool}" >/dev/null 2>&1 || { echo "bootstrap: ${tool} is not installed" >&2; exit 1; }
done

# linkerd has no versions.env pin (see the install step below), but an old
# `stable-2.x` CLI genuinely cannot install here: it renders CronJob on
# batch/v1beta1, removed since Kubernetes 1.25, and fails outright against
# this cluster's 1.31 -- hit live this session with a Homebrew-installed
# stable-2.11.2. Fail fast with the fix rather than a bare CronJob error deep
# in `kubectl apply`.
case "$(linkerd version --client --short 2>/dev/null || true)" in
  stable-2.*)
    echo "bootstrap: linkerd CLI is a pre-2023 stable-2.x release, incompatible with k8s 1.25+" >&2
    echo "bootstrap: run 'brew upgrade linkerd' (or equivalent) and retry" >&2
    exit 1
    ;;
esac

# Local private keys and certificates (gateway TLS, Linkerd identity) are
# generated on first run into a gitignored directory, never committed.
"${here}/local-secrets.sh"

if kind get clusters 2>/dev/null | grep -qx "${CLUSTER}"; then
  echo "bootstrap: kind cluster '${CLUSTER}' already exists"
else
  echo "bootstrap: creating kind cluster '${CLUSTER}' (${KIND_NODE_IMAGE})"
  kind create cluster --config "${here}/kind-config.yaml" --wait 120s
fi
kubectl config use-context "${CONTEXT}" >/dev/null

apply_pinned() {
  # $1 = human name, $2 = URL. Server-side apply: CNPG's CRDs exceed the
  # client-side annotation size limit.
  echo "bootstrap: applying $1 ($2)"
  kubectl apply --server-side --force-conflicts -f "$2"
}

# 1. CNI. Nothing schedules until this is up (nodes stay NotReady).
if ! kubectl -n kube-system get daemonset calico-node >/dev/null 2>&1; then
  apply_pinned "Calico ${CALICO_VERSION}" "${CALICO_MANIFEST}"
fi
echo "bootstrap: waiting for Calico"
kubectl -n kube-system rollout status daemonset/calico-node --timeout=300s
kubectl -n kube-system rollout status deployment/calico-kube-controllers --timeout=300s
kubectl wait --for=condition=Ready node --all --timeout=300s

# 1b. Storage traffic encryption (ADR-0030): node-to-node WireGuard tunnels
# for every pod, including the unmeshed datastore tier (ADR-0029). A plain
# `kubectl apply` is idempotent here -- FelixConfiguration's `default` is a
# singleton Calico already created in step 1, so this only ever patches it.
kubectl apply -f "${here}/calico-wireguard-config.yaml"

# 2. metrics-server, for the CPU HPAs. kind's kubelets serve self-signed certs.
if ! kubectl -n kube-system get deployment metrics-server >/dev/null 2>&1; then
  apply_pinned "metrics-server ${METRICS_SERVER_VERSION}" "${METRICS_SERVER_MANIFEST}"
  kubectl -n kube-system patch deployment metrics-server --type=json \
    -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
fi

# 3. CloudNativePG.
if ! kubectl get crd clusters.postgresql.cnpg.io >/dev/null 2>&1; then
  apply_pinned "CloudNativePG ${CNPG_VERSION}" "${CNPG_MANIFEST}"
fi

# 4. RabbitMQ cluster operator.
if ! kubectl get crd rabbitmqclusters.rabbitmq.com >/dev/null 2>&1; then
  apply_pinned "RabbitMQ cluster operator ${RABBITMQ_OPERATOR_VERSION}" "${RABBITMQ_OPERATOR_MANIFEST}"
fi

# 5. Envoy Gateway -- Gateway API CRDs, Envoy Gateway's own CRDs and its
# controller, one manifest (Task 10.1, ADR-0028).
if ! kubectl get crd gatewayclasses.gateway.networking.k8s.io >/dev/null 2>&1; then
  apply_pinned "Envoy Gateway ${ENVOY_GATEWAY_VERSION}" "${ENVOY_GATEWAY_MANIFEST}"
fi

echo "bootstrap: waiting for operators"
kubectl -n kube-system rollout status deployment/metrics-server --timeout=180s
kubectl -n cnpg-system rollout status deployment/cnpg-controller-manager --timeout=180s
kubectl -n rabbitmq-system rollout status deployment/rabbitmq-cluster-operator --timeout=180s
kubectl -n envoy-gateway-system rollout status deployment/envoy-gateway --timeout=180s

# 5b. Task 10.3: Redis-backed global rate limiting, the controller's own
# install-time config -- see envoy-gateway-config.yaml's own header for why
# this is a full ConfigMap replacement rather than a patch, and why it lives
# here rather than in infrastructure/k8s/base. The rate limit service only
# reaches the platform's Valkey once `make skaffold-run` deploys it; applying
# this before that exists is fine (the ConfigMap is declarative, and the
# generated envoy-ratelimit pod simply crash-loops until Valkey resolves,
# the same "converges once its dependency exists" bootstrapping order this
# script already relies on elsewhere).
apply_pinned "Envoy Gateway rate-limit backend (Task 10.3)" "${here}/envoy-gateway-config.yaml"
kubectl -n envoy-gateway-system rollout restart deployment/envoy-gateway
kubectl -n envoy-gateway-system rollout status deployment/envoy-gateway --timeout=180s

# 5c. Linkerd -- mesh mTLS for service-to-service traffic (Task 10.4,
# ADR-0028/ADR-0029). Unlike every install above, Linkerd has no plain
# release-manifest URL to pin in versions.env: its own control-plane
# manifest is rendered by the `linkerd` CLI, not fetched, because it must be
# stamped with a trust anchor and identity-issuer certificate at render
# time. So `linkerd` joins kind/kubectl in the tool-existence check above
# instead -- the platform's pin is "whichever `linkerd` client is on PATH",
# the same un-pinned-in-this-file treatment those two already get, not an
# omission. CRDs first (their own step -- the control-plane chart requires
# them applied already), then the control plane using the local trust anchor
# and issuer local-secrets.sh generated into overlays/local/generated/ (never
# committed; created on first run, reused after) and
# --default-inbound-policy=deny, so every
# meshed pod is default-denied until a Server/AuthorizationPolicy names an
# explicit caller (infrastructure/k8s/base/linkerd-policy/).
if ! kubectl get crd servers.policy.linkerd.io >/dev/null 2>&1; then
  echo "bootstrap: installing Linkerd CRDs"
  linkerd install --crds | kubectl apply --server-side --force-conflicts -f -
fi
generated="${here}/../overlays/local/generated"
if ! kubectl get ns linkerd >/dev/null 2>&1; then
  echo "bootstrap: installing Linkerd control plane"
  linkerd install \
    --identity-trust-anchors-file "${generated}/linkerd-ca.crt" \
    --identity-issuer-certificate-file "${generated}/linkerd-issuer.crt" \
    --identity-issuer-key-file "${generated}/linkerd-issuer.key" \
    --default-inbound-policy=deny \
    | kubectl apply --server-side --force-conflicts -f -
fi
kubectl -n linkerd rollout status deployment/linkerd-identity --timeout=180s
kubectl -n linkerd rollout status deployment/linkerd-destination --timeout=180s
kubectl -n linkerd rollout status deployment/linkerd-proxy-injector --timeout=180s

# 6. The platform namespace. Everything in overlays/local lands here.
kubectl get namespace fondaria >/dev/null 2>&1 || kubectl create namespace fondaria
kubectl config set-context "${CONTEXT}" --namespace=fondaria >/dev/null

echo "bootstrap: cluster '${CLUSTER}' is ready; deploy with 'make skaffold-run'"
