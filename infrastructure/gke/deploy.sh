#!/bin/sh
# Builds every service image for linux/amd64 (GKE's n2 nodes; this repo's
# developers build on arm64 Macs), pushes it to the environment's own Artifact
# Registry and deploys infrastructure/k8s/overlays/gke via skaffold's `gke`
# profile. The registry is deleted with the project.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
root="${here}/../.."
# shellcheck disable=SC1091
. "${here}/config.env"
[ -s "${here}/.state" ] || { echo "gke-deploy: no environment; run make gke-up first" >&2; exit 1; }
# shellcheck disable=SC1091
. "${here}/.state"

registry="${GKE_REGION}-docker.pkg.dev"
# Jib pushes through Docker's credential helper for this one registry host.
gcloud auth configure-docker "${registry}" --quiet >/dev/null 2>&1

"${here}/secrets.sh"

cd "${root}"
case "${GKE_PROFILE:-full}" in small) profile=gke-small ;; *) profile=gke ;; esac
skaffold run --profile="${profile}" \
  --kube-context="${GKE_CONTEXT}" \
  --default-repo="${registry}/${GKE_PROJECT}/fondaria" \
  --platform=linux/amd64 \
  --status-check=true
