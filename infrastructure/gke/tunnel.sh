#!/bin/sh
# Opens the only ways into the GKE load-test environment: kubectl port-forwards
# through the authenticated control plane (no public endpoint exists).
#
#   localhost:30001  Grafana (admin password printed below)
#   localhost:30080  contribution-api   } the same ports `make demo-port-forward`
#   localhost:30081  balance-api        } uses against kind, so the demo-* targets
#   localhost:30082  valuation-api      } run unchanged
#
# Then, in the same shell, `eval "$(infrastructure/gke/tunnel.sh --env)"` points
# demo-tools at this cluster and its random Keycloak client secret.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
[ -s "${here}/.state" ] || { echo "gke-tunnel: no environment; run make gke-up first" >&2; exit 1; }
# shellcheck disable=SC1091
. "${here}/.state"
generated="${here}/../k8s/overlays/gke/generated"
client_secret="$(sed -n 's/^internal-services=//p' "${generated}/keycloak-client-secrets.env")"

if [ "${1:-}" = "--env" ]; then
  printf "export KUBE_CONTEXT='%s' KEYCLOAK_CLIENT_SECRET='%s'\n" "${GKE_CONTEXT}" "${client_secret}"
  exit 0
fi

forward() {
  # $1 = service, $2 = local port, $3 = service port. Restarts on drop.
  pkill -f "kubectl.*port-forward.*svc/$1 $2:" 2>/dev/null || true
  nohup sh -c "while true; do kubectl --context='${GKE_CONTEXT}' -n fondaria port-forward svc/$1 $2:$3 >/dev/null 2>&1; sleep 1; done" \
    </dev/null >/dev/null 2>&1 &
}
forward grafana 30001 3000
forward contribution-api 30080 80
forward balance-api 30081 80
forward valuation-api 30082 80
sleep 3

echo "gke-tunnel: Grafana  http://localhost:30001  user admin, password:"
echo "            $(sed -n 's/^admin-password=//p' "${generated}/grafana-admin-secret.env")"
echo "gke-tunnel: for demo-tools in this shell:"
echo "            eval \"\$(infrastructure/gke/tunnel.sh --env)\""
echo "gke-tunnel: stop with: pkill -f 'kubectl.*port-forward'"
