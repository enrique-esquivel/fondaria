#!/bin/sh
# Destroys the GKE load-test environment. A project up.sh created is deleted
# whole: cluster, disks, NAT, registry, service accounts and the dead man's
# switch go with it, so nothing is left to bill or to abuse. A kept project
# (GKE_PROJECT, see config.env) survives: everything up.sh built in it is
# deleted one by one, then its billing is unlinked, so it cannot bill even for
# something this script missed. Either way it then removes the local
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
# shellcheck disable=SC1091
. "${here}/config.env"
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

# How long the big machines billed (stateful-pool.sh started the clock).
if [ -n "${GKE_STATEFUL_SINCE:-}" ]; then
  echo "gke-down: stateful pool up since ${GKE_STATEFUL_SINCE}: $(SINCE="${GKE_STATEFUL_SINCE}" python3 -c '
import datetime as d, os
s = d.datetime.strptime(os.environ["SINCE"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=d.timezone.utc)
m = int((d.datetime.now(d.timezone.utc) - s).total_seconds() // 60)
print(f"{m // 60} h {m % 60:02d} min")') on ${GKE_STATEFUL_MACHINE:-?} x $(( ${GKE_STATEFUL_NODES_PER_ZONE:-0} * 3 ))"
fi

P="${GKE_PROJECT}"
confirm() {
  [ "${yes}" = true ] && return 0
  printf 'gke-down: %s? [y/N] ' "$1"
  read -r answer
  [ "${answer}" = y ] || [ "${answer}" = Y ] || { echo "gke-down: aborted"; exit 1; }
}

if [ "${GKE_KEEP_PROJECT:-false}" != true ]; then
  if gcloud projects describe "${P}" --format='value(lifecycleState)' 2>/dev/null | grep -qx ACTIVE; then
    confirm "delete project ${P} and everything in it"
    echo "gke-down: deleting project ${P} (billing stops now; Google purges it after 30 days)"
    gcloud projects delete "${P}" --quiet
  else
    echo "gke-down: project ${P} is already gone (or the dead man's switch fired)"
  fi
else
  confirm "delete the cluster and everything gke-up built in project ${P} (the project itself is kept)"
  # Compute and GKE refuse every call, deletes included, while billing is
  # unlinked -- the state the dead man's switch leaves behind.
  if [ "$(gcloud billing projects describe "${P}" --format='value(billingEnabled)')" != "True" ]; then
    [ -n "${GKE_BILLING_ACCOUNT:-}" ] \
      || { echo "gke-down: billing is unlinked; re-run with GKE_BILLING_ACCOUNT set to delete what is left" >&2; exit 1; }
    echo "gke-down: relinking billing for the teardown (unlinked again at the end)"
    gcloud billing projects link "${P}" --billing-account="${GKE_BILLING_ACCOUNT}" >/dev/null
  fi
  G="--project=${P} --quiet"
  # shellcheck disable=SC2086
  {
    if [ -n "${GKE_ZONE:-}" ] && gcloud container clusters describe "${GKE_CLUSTER}" --zone="${GKE_ZONE}" $G >/dev/null 2>&1; then
      echo "gke-down: deleting cluster ${GKE_CLUSTER} (~5 min)"
      gcloud container clusters delete "${GKE_CLUSTER}" --zone="${GKE_ZONE}" $G
    fi
    # The persistent disks behind the cluster's volumes outlive it: the CSI
    # driver that would delete them is gone with the cluster.
    gcloud compute disks list $G --filter='description~"kubernetes.io/created-for"' \
        --format='value(name,zone.basename())' | while read -r disk zone; do
      echo "gke-down: deleting volume disk ${disk}"
      gcloud compute disks delete "${disk}" --zone="${zone}" $G >/dev/null
    done
    # A capacity probe interrupted between create and delete.
    gcloud compute instances list $G --filter='labels.purpose=capacity-probe' \
        --format='value(name,zone.basename())' | while read -r vm zone; do
      gcloud compute instances delete "${vm}" --zone="${zone}" $G >/dev/null
    done
    gcloud compute routers list $G --filter='network~/fondaria$' --format='value(name,region.basename())' \
        | while read -r router region; do
      echo "gke-down: deleting Cloud NAT router ${router}"
      gcloud compute routers delete "${router}" --region="${region}" $G >/dev/null
    done
    for rule in $(gcloud compute firewall-rules list $G --filter='network~/fondaria$' --format='value(name)'); do
      gcloud compute firewall-rules delete "${rule}" $G >/dev/null
    done
    gcloud compute networks subnets list $G --network=fondaria --format='value(name,region.basename())' 2>/dev/null \
        | while read -r subnet region; do
      gcloud compute networks subnets delete "${subnet}" --region="${region}" $G >/dev/null
    done
    if gcloud compute networks describe fondaria $G >/dev/null 2>&1; then
      echo "gke-down: deleting VPC fondaria"
      gcloud compute networks delete fondaria $G >/dev/null
    fi
    for location in $(gcloud artifacts repositories list $G --filter='name~/fondaria$' --format='value(name.segment(3))' 2>/dev/null); do
      echo "gke-down: deleting image registry fondaria (${location})"
      gcloud artifacts repositories delete fondaria --location="${location}" $G >/dev/null
    done
    if [ -n "${GKE_HOME_REGION:-}" ] \
        && gcloud scheduler jobs describe fondaria-ttl --location="${GKE_HOME_REGION}" $G >/dev/null 2>&1; then
      gcloud scheduler jobs delete fondaria-ttl --location="${GKE_HOME_REGION}" $G >/dev/null
    fi
    for sa in gke-nodes deadman; do
      email="${sa}@${P}.iam.gserviceaccount.com"
      gcloud iam service-accounts describe "${email}" $G >/dev/null 2>&1 || continue
      for role in $(gcloud projects get-iam-policy "${P}" --flatten='bindings[].members' \
          --filter="bindings.members:serviceAccount:${email}" --format='value(bindings.role)'); do
        gcloud projects remove-iam-policy-binding "${P}" --member="serviceAccount:${email}" \
          --role="${role}" --condition=None --quiet >/dev/null
      done
      echo "gke-down: deleting service account ${sa}"
      gcloud iam service-accounts delete "${email}" $G >/dev/null
    done
  }
  echo "gke-down: unlinking billing from ${P} (gke-up links it again)"
  gcloud billing projects unlink "${P}" >/dev/null
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
