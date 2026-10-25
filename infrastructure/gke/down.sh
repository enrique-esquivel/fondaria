#!/bin/sh
# Destroys the GKE load-test environment by deleting its whole project:
# cluster, disks, NAT, registry, service accounts and the dead man's switch go
# with it, so nothing is left to bill or to abuse. Then removes the local
# kubeconfig entry and, unless --keep-login, revokes this machine's gcloud
# credentials so no token is left behind.
#
# The billing-account budget alert is kept on purpose: it watches every
# project on the account, not just this one.
#
#   down.sh            asks for confirmation
#   down.sh --yes      no prompt
#   down.sh --keep-login
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
state="${here}/.state"
yes=false
revoke=true
for arg in "$@"; do
  case "${arg}" in
    --yes) yes=true ;;
    --keep-login) revoke=false ;;
    *) echo "gke-down: unknown argument ${arg}" >&2; exit 2 ;;
  esac
done

[ -s "${state}" ] || { echo "gke-down: no environment recorded in ${state}; nothing to delete"; exit 0; }
# shellcheck disable=SC1090
. "${state}"

pkill -f "kubectl.*port-forward" 2>/dev/null || true

if gcloud projects describe "${GKE_PROJECT}" --format='value(lifecycleState)' 2>/dev/null | grep -qx ACTIVE; then
  if [ "${yes}" != true ]; then
    printf 'gke-down: delete project %s and everything in it? [y/N] ' "${GKE_PROJECT}"
    read -r answer
    [ "${answer}" = y ] || [ "${answer}" = Y ] || { echo "gke-down: aborted"; exit 1; }
  fi
  echo "gke-down: deleting project ${GKE_PROJECT} (billing stops now; Google purges it after 30 days)"
  gcloud projects delete "${GKE_PROJECT}" --quiet
else
  echo "gke-down: project ${GKE_PROJECT} is already gone (or the dead man's switch fired)"
fi

if [ -n "${GKE_CONTEXT:-}" ]; then
  kubectl config delete-context "${GKE_CONTEXT}" >/dev/null 2>&1 || true
  kubectl config delete-cluster "${GKE_CONTEXT}" >/dev/null 2>&1 || true
  kubectl config delete-user "${GKE_CONTEXT}" >/dev/null 2>&1 || true
  if kubectl config get-contexts -o name 2>/dev/null | grep -qx kind-fondaria; then
    kubectl config use-context kind-fondaria >/dev/null
  fi
fi
rm -f "${state}"

if [ "${revoke}" = true ]; then
  echo "gke-down: revoking this machine's gcloud credentials (sign in again with: gcloud auth login)"
  gcloud auth revoke --all --quiet >/dev/null 2>&1 || true
fi
echo "gke-down: done. Verify nothing is left: https://console.cloud.google.com/billing"
