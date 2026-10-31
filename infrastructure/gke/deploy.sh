#!/bin/sh
# Makes sure every service image is in the environment's own Artifact Registry
# (build.sh: a no-op when up.sh already pushed this source tree), THEN creates
# the stateful pool (stateful-pool.sh, full profile only), THEN deploys
# infrastructure/k8s/overlays/gke via skaffold's `gke` profile. The order keeps
# the expensive machines off the clock while images build. The registry is
# deleted with the project.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
root="${here}/../.."
# shellcheck disable=SC1091
. "${here}/config.env"
[ -s "${here}/.state" ] || { echo "gke-deploy: no environment; run make gke-up first" >&2; exit 1; }
# shellcheck disable=SC1091
. "${here}/.state"

"${here}/secrets.sh"

cd "${root}"
case "${GKE_PROFILE:-full}" in small) profile=gke-small ;; *) profile=gke ;; esac
artifacts="${here}/.build-artifacts.json"
"${here}/build.sh"

"${here}/stateful-pool.sh"

echo "gke-deploy: deploying ($(date -u +%H:%M:%SZ))"
skaffold deploy --profile="${profile}" \
  --kube-context="${GKE_CONTEXT}" \
  --build-artifacts="${artifacts}" \
  --status-check=true
echo "gke-deploy: deployed ($(date -u +%H:%M:%SZ))"
# The pool exists by now, so this only prints the cost window again.
"${here}/stateful-pool.sh"
