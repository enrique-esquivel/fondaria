#!/bin/sh
# Creates the full profile's stateful node pool -- most of the environment's
# vCPUs -- and starts the cost window. deploy.sh runs it after the images are
# in the registry, so the big machines never wait on a build. Re-arms the dead
# man's switch to GKE_STATEFUL_TTL_MINUTES from now: the longest the big pool
# can bill for if nobody runs `make gke-down`.
#
# A no-op for the small profile (one shared pool, built by up.sh) and when the
# pool already exists. Prints the cost window either way.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
. "${here}/config.env"
state="${here}/.state"
[ -s "${state}" ] || { echo "gke-stateful: no environment; run make gke-up first" >&2; exit 1; }
# shellcheck disable=SC1090
. "${state}"

log() { echo "gke-stateful: $*"; }
die() { echo "gke-stateful: $*" >&2; exit 1; }
# shellcheck disable=SC1091
. "${here}/lib.sh"

P="${GKE_PROJECT}"
GKE_KEEP_PROJECT="${GKE_KEEP_PROJECT:-false}"
stateful_per_zone="${GKE_STATEFUL_NODES_PER_ZONE:-0}"
[ -n "${GKE_STATELESS_MACHINE:-}" ] || die "no stateless machine in ${state}; re-run make gke-up"
stateless_vcpus=$(( ${GKE_STATELESS_NODES_PER_ZONE:-1} * 3 * ${GKE_STATELESS_MACHINE##*-} ))
[ "${stateful_per_zone}" -gt 0 ] || { log "profile ${GKE_PROFILE:-small} has no stateful pool"; exit 0; }
[ -n "${GKE_STATEFUL_MACHINE:-}" ] || die "no stateful machine in ${state}; re-run make gke-up"
stateful_vcpus=$(( stateful_per_zone * 3 * ${GKE_STATEFUL_MACHINE##*-} ))

if ! gcloud container node-pools describe stateful --cluster="${GKE_CLUSTER}" --zone="${GKE_ZONE}" \
    --project="${P}" >/dev/null 2>&1; then
  # Re-armed BEFORE the pool exists, so there is no moment the big machines
  # bill under a later deadline than this one.
  arm_deadman "${GKE_STATEFUL_TTL_MINUTES}"
  log "dead man's switch re-armed: ${deadman_does_log} at ${GKE_EXPIRES_AT}"
  log "creating the stateful pool: $((stateful_per_zone * 3)) x ${GKE_STATEFUL_MACHINE}" \
    "(tainted: only the datastores, which tolerate it, land here; ~5 min)"
  remember GKE_STATEFUL_SINCE "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  gcloud container node-pools create stateful --project="${P}" --zone="${GKE_ZONE}" \
    --cluster="${GKE_CLUSTER}" --node-locations="${GKE_NODE_ZONES}" \
    --num-nodes="${stateful_per_zone}" \
    --node-labels=fondaria.example/node-pool=stateful \
    --node-taints=fondaria.example/node-pool=stateful:NoSchedule \
    --machine-type="${GKE_STATEFUL_MACHINE}" \
    --service-account="gke-nodes@${P}.iam.gserviceaccount.com" --workload-metadata=GKE_METADATA \
    --shielded-secure-boot --shielded-integrity-monitoring \
    --disk-type="${GKE_BOOT_DISK_TYPE}" --disk-size="${GKE_BOOT_DISK_GB}"
  # shellcheck disable=SC1090
  . "${state}"
fi

log "COST WINDOW: $((stateless_vcpus + stateful_vcpus)) vCPU (${stateful_vcpus} stateful + ${stateless_vcpus} stateless)" \
  "since ${GKE_STATEFUL_SINCE:-unknown}; the dead man's switch ends it at ${GKE_EXPIRES_AT:-unknown}."
log "run make gke-down as soon as the load finishes."
