#!/bin/sh
# Creates the throwaway GKE load-test environment (ADR-0036) in a brand-new
# Google Cloud project, then installs the operators the platform's manifests
# assume. Deploy the platform afterwards with `make gke-deploy`.
#
# Security posture, in the order it is built:
#   1. A new project per run, or one you created and keep (GKE_PROJECT). Only
#      the APIs below are enabled -- no Vertex AI, no Gemini/Generative
#      Language API, no API keys are ever created.
#   2. A dead man's switch BEFORE anything billable: GKE_TTL_HOURS after this
#      script starts, a Cloud Scheduler job deletes a project up.sh created, or
#      unlinks the billing of a kept one. It runs as a service account that can
#      do nothing else (projectDeleter, or projectManager on billing, on this
#      one project).
#   3. A billing-account-wide budget alert.
#   4. Private nodes (no public IPs), egress only through Cloud NAT, no SSH
#      firewall rule at all.
#   5. Control plane reachable only from this machine's public IP, and still
#      behind Google sign-in.
#   6. Nodes run as a service account that can only pull images; Workload
#      Identity keeps even that out of pods. A compromised pod holds no Google
#      credential.
#   7. Pod-to-pod traffic between nodes is WireGuard-encrypted by GKE Dataplane
#      V2 (inter-node transparent encryption) -- the GKE equivalent of the kind
#      cluster's Calico WireGuard (ADR-0030).
#
# Idempotent: every step checks before it creates, so re-running after a
# failure resumes. The project id lives in infrastructure/gke/.state.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck disable=SC1091
. "${here}/config.env"
# shellcheck disable=SC1091
. "${here}/../k8s/bootstrap/versions.env"
state="${here}/.state"

log() { echo "gke-up: $*"; }
die() { echo "gke-up: $*" >&2; exit 1; }

# A service account is returned by `create` before IAM can bind or use it
# ("Service account ... does not exist" for up to a minute or two). Retries a
# command until it succeeds, printing its error only if it never does.
retry() {
  attempt=1
  until err="$("$@" 2>&1 >/dev/null)"; do
    [ "${attempt}" -lt 24 ] || { echo "${err}" >&2; return 1; }
    [ "${attempt}" -eq 1 ] && log "waiting for a new service account to propagate in IAM"
    attempt=$((attempt + 1))
    sleep 5
  done
}

for tool in gcloud kubectl linkerd openssl python3 curl; do
  command -v "${tool}" >/dev/null 2>&1 || die "${tool} is not installed (see infrastructure/gke/README.md)"
done
command -v gke-gcloud-auth-plugin >/dev/null 2>&1 \
  || die "gke-gcloud-auth-plugin is missing: gcloud components install gke-gcloud-auth-plugin"
[ -n "${GKE_BILLING_ACCOUNT}" ] \
  || die "set GKE_BILLING_ACCOUNT (gcloud billing accounts list), e.g. GKE_BILLING_ACCOUNT=0X0X0X-0X0X0X-0X0X0X make gke-up"
account="$(gcloud config get-value account 2>/dev/null || true)"
[ -n "${account}" ] || die "not signed in: gcloud auth login"

# ---------------------------------------------------------------- project ---
requested_project="${GKE_PROJECT}"
if [ -s "${state}" ]; then
  # shellcheck disable=SC1090
  . "${state}"
fi
# A profile asked for explicitly wins over the saved one, unless a cluster of
# the other profile already exists: switching means gke-down first.
if [ -n "${GKE_PROFILE_REQUESTED}" ] && [ "${GKE_PROFILE_REQUESTED}" != "${GKE_PROFILE}" ]; then
  if [ -n "${GKE_ZONE:-}" ] && gcloud container clusters describe "${GKE_CLUSTER}" --zone="${GKE_ZONE}" \
      --project="${GKE_PROJECT}" >/dev/null 2>&1; then
    die "this environment's cluster is profile ${GKE_PROFILE}; run make gke-down before switching to ${GKE_PROFILE_REQUESTED}"
  fi
  GKE_PROFILE="${GKE_PROFILE_REQUESTED}"
fi
case "${GKE_PROFILE}" in
  full) GKE_STATEFUL_NODES_PER_ZONE="${GKE_STATEFUL_NODES_PER_ZONE:-2}" ;;
  small) GKE_STATEFUL_NODES_PER_ZONE="${GKE_STATEFUL_NODES_PER_ZONE:-0}" ;;
  *) die "GKE_PROFILE must be full or small, not ${GKE_PROFILE}" ;;
esac
# Records KEY=value in .state, replacing an earlier value.
remember() {
  touch "${state}"
  grep -v "^$1=" "${state}" > "${state}.tmp" || true
  printf '%s=%s\n' "$1" "$2" >> "${state}.tmp"
  mv "${state}.tmp" "${state}"
}
if [ -n "${requested_project}" ] && [ -n "${GKE_PROJECT}" ] && [ "${requested_project}" != "${GKE_PROJECT}" ]; then
  die "this environment is project ${GKE_PROJECT}; run make gke-down before using ${requested_project}"
fi
if [ -z "${GKE_PROJECT}" ]; then
  if [ -n "${requested_project}" ]; then
    GKE_PROJECT="${requested_project}"
    GKE_KEEP_PROJECT=true
  else
    GKE_PROJECT="${GKE_PROJECT_PREFIX}-$(openssl rand -hex 3)"
    GKE_KEEP_PROJECT=false
  fi
  remember GKE_PROJECT "${GKE_PROJECT}"
  remember GKE_KEEP_PROJECT "${GKE_KEEP_PROJECT}"
fi
# A .state from before GKE_KEEP_PROJECT existed always meant a created project.
GKE_KEEP_PROJECT="${GKE_KEEP_PROJECT:-false}"
P="${GKE_PROJECT}"
# shellcheck disable=SC2086
set -- ${GKE_REGIONS}
home_region="$1"
remember GKE_HOME_REGION "${home_region}"

if ! gcloud projects describe "${P}" >/dev/null 2>&1; then
  [ "${GKE_KEEP_PROJECT}" = false ] \
    || die "project ${P} does not exist or ${account} cannot see it (gcloud projects list)"
  log "creating project ${P}"
  gcloud projects create "${P}" --name="fondaria load test" --labels=purpose=load-test,owner=fondaria
fi
remember GKE_BILLING_ACCOUNT "${GKE_BILLING_ACCOUNT}"
if [ "$(gcloud billing projects describe "${P}" --format='value(billingEnabled)')" != "True" ]; then
  log "linking billing account"
  gcloud billing projects link "${P}" --billing-account="${GKE_BILLING_ACCOUNT}" >/dev/null
fi

log "enabling only the APIs this environment needs"
gcloud services enable --project="${P}" \
  compute.googleapis.com \
  container.googleapis.com \
  artifactregistry.googleapis.com \
  iam.googleapis.com \
  cloudscheduler.googleapis.com \
  cloudresourcemanager.googleapis.com \
  cloudbilling.googleapis.com \
  billingbudgets.googleapis.com

# Enabling Compute creates a `default` network whose firewall rules admit SSH
# and RDP from the whole internet. Nothing here uses it; delete it before
# anything could ever be attached to it.
if gcloud compute networks describe default --project="${P}" >/dev/null 2>&1; then
  log "deleting the auto-created default network and its open SSH/RDP rules"
  for rule in $(gcloud compute firewall-rules list --project="${P}" --filter="network~/default$" --format='value(name)'); do
    gcloud compute firewall-rules delete "${rule}" --project="${P}" --quiet >/dev/null
  done
  gcloud compute networks delete default --project="${P}" --quiet >/dev/null
fi

# ------------------------------------------------------ dead man's switch ---
# Ends the PROJECT's spending, not just the cluster's: a deleted cluster leaves
# its persistent disks, NAT and registry behind, still billing. A project
# up.sh created is deleted; a kept one (GKE_PROJECT) has its billing unlinked,
# which stops every charge at once and deletes nothing -- the next gke-up links
# it again. Re-armed from now on every run, so a project kept waiting for a
# quota approval is not shut down in the middle of the run that finally uses it.
deadman="deadman@${P}.iam.gserviceaccount.com"
if [ "${GKE_KEEP_PROJECT}" = true ]; then
  deadman_role=roles/billing.projectManager
  deadman_does="Unlinks this project's billing at its TTL; nothing else"
  deadman_does_log="unlinks project ${P}'s billing"
  set -- --uri="https://cloudbilling.googleapis.com/v1/projects/${P}/billingInfo" --http-method=PUT \
    --headers=Content-Type=application/json --message-body='{"billingAccountName":""}'
else
  deadman_role=roles/resourcemanager.projectDeleter
  deadman_does="Deletes this project at its TTL; nothing else"
  deadman_does_log="deletes project ${P}"
  set -- --uri="https://cloudresourcemanager.googleapis.com/v3/projects/${P}" --http-method=DELETE
fi
if ! gcloud iam service-accounts describe "${deadman}" --project="${P}" >/dev/null 2>&1; then
  gcloud iam service-accounts create deadman --project="${P}" --display-name="${deadman_does}"
fi
retry gcloud projects add-iam-policy-binding "${P}" --condition=None --quiet \
  --member="serviceAccount:${deadman}" --role="${deadman_role}"
expires="$(TTL="${GKE_TTL_HOURS}" python3 -c '
import datetime as d, os
t = d.datetime.now(d.timezone.utc).replace(second=0, microsecond=0) + d.timedelta(hours=int(os.environ["TTL"]))
print(t.strftime("%Y-%m-%dT%H:%MZ"), f"{t.minute} {t.hour} {t.day} {t.month} *")')"
expires_at="${expires%% *}"
cron="${expires#* }"
if gcloud scheduler jobs describe fondaria-ttl --location="${home_region}" --project="${P}" >/dev/null 2>&1; then
  gcloud scheduler jobs update http fondaria-ttl --project="${P}" --location="${home_region}" \
    --schedule="${cron}" --time-zone="Etc/UTC" >/dev/null
  gcloud scheduler jobs resume fondaria-ttl --project="${P}" --location="${home_region}" >/dev/null
else
  retry gcloud scheduler jobs create http fondaria-ttl --project="${P}" --location="${home_region}" \
    --schedule="${cron}" --time-zone="Etc/UTC" "$@" \
    --oauth-service-account-email="${deadman}" \
    --oauth-token-scope="https://www.googleapis.com/auth/cloud-platform" \
    --attempt-deadline=60s --max-retry-attempts=5
fi
remember GKE_EXPIRES_AT "${expires_at}"
log "dead man's switch armed: ${deadman_does_log} at ${expires_at}"

# ------------------------------------------------------------ budget alert ---
if ! gcloud billing budgets list --billing-account="${GKE_BILLING_ACCOUNT}" --billing-project="${P}" \
    --format='value(displayName)' 2>/dev/null | grep -qx "fondaria-load-test-guard"; then
  currency="$(gcloud billing accounts describe "${GKE_BILLING_ACCOUNT}" --format='value(currencyCode)')"
  amount="${GKE_BUDGET_AMOUNT}"
  case "${currency}" in
    USD) amount="${amount:-${GKE_BUDGET_USD}}" ;;
    MXN) amount="${amount:-${GKE_BUDGET_MXN}}" ;;
  esac
  [ -n "${amount}" ] \
    || die "billing account is in ${currency}; set GKE_BUDGET_AMOUNT (a number in ${currency}) and re-run"
  log "creating a ${amount} ${currency} budget alert on the whole billing account"
  gcloud billing budgets create --billing-account="${GKE_BILLING_ACCOUNT}" --billing-project="${P}" \
    --display-name="fondaria-load-test-guard" --budget-amount="${amount}${currency}" \
    --threshold-rule=percent=0.25 --threshold-rule=percent=0.5 \
    --threshold-rule=percent=0.9 --threshold-rule=percent=1.0 >/dev/null
fi

# -------------------------------------------------------------- placement ---
[ "${GKE_NODE_VCPUS}" -ge 4 ] || die "GKE_NODE_VCPUS must be at least 4 (see config.env)"
nodes=$(( (GKE_STATEFUL_NODES_PER_ZONE + GKE_STATELESS_NODES_PER_ZONE) * 3 ))
need_cpus=$(( nodes * GKE_NODE_VCPUS ))
"${here}/secrets.sh" >/dev/null
# Disk the profile's data volumes claim, SSD and standard, in GB.
case "${GKE_PROFILE}" in small) overlay=gke-small ;; *) overlay=gke ;; esac
claims="$(kubectl kustomize "${here}/../k8s/overlays/${overlay}" | python3 "${here}/ssd-claims.py")"
need_ssd="${claims% *}"
need_std="${claims#* }"
boot_gb=$(( nodes * GKE_BOOT_DISK_GB ))
case "${GKE_BOOT_DISK_TYPE}" in
  pd-standard) need_std=$(( need_std + boot_gb )) ;;
  *) need_ssd=$(( need_ssd + boot_gb )) ;;
esac
log "profile ${GKE_PROFILE}: the cluster needs ${nodes} nodes x ${GKE_NODE_VCPUS} vCPU = ${need_cpus} vCPU, ${need_ssd} GB SSD, ${need_std} GB standard disk"

family_quota() {
  case "$1" in
    n2) echo N2_CPUS ;; n2d) echo N2D_CPUS ;; n4) echo N4_CPUS ;; c3) echo C3_CPUS ;;
    c3d) echo C3D_CPUS ;; c2d) echo C2D_CPUS ;; t2d) echo T2D_CPUS ;; *) echo "" ;;
  esac
}
global_quotas="$(gcloud compute project-info describe --project="${P}" --format=json)"

# Prints one line per quota that is too low; silent when everything fits.
# $1 = region, $2 = machine family.
quota_shortfalls() {
  gcloud compute regions describe "$1" --project="${P}" --format=json \
    | GLOBAL="${global_quotas}" REGION="$1" FAMILY_QUOTA="$(family_quota "$2")" \
      CPUS="${need_cpus}" SSD="${need_ssd}" STD="${need_std}" python3 -c '
import json, os, sys
region, glob, e = json.load(sys.stdin), json.loads(os.environ["GLOBAL"]), os.environ
def free(doc, metric):
    for q in doc["quotas"]:
        if q["metric"] == metric:
            return q["limit"] - q["usage"], q["limit"]
    return None, None
checks = [(glob, "CPUS_ALL_REGIONS", "CPUS", "global"), (region, "CPUS", "CPUS", e["REGION"]),
          (region, "SSD_TOTAL_GB", "SSD", e["REGION"]), (region, "DISKS_TOTAL_GB", "STD", e["REGION"])]
if e["FAMILY_QUOTA"]:
    checks.append((region, e["FAMILY_QUOTA"], "CPUS", e["REGION"]))
for doc, metric, need_key, where in checks:
    need = int(e[need_key])
    available, limit = free(doc, metric)
    if need and available is not None and available < need:
        print(f"{metric} ({where}): limit {int(limit)}, free {int(available)}, need {need}")
'
}

# Creates and immediately deletes one VM of the machine type in the zone: the
# only reliable test that the zone has capacity. No external IP, no service
# account, no firewall rule reaches it. $1 = zone, $2 = machine type.
probe_zone() {
  name="fondaria-probe-$(openssl rand -hex 3)"
  if out="$(gcloud compute instances create "${name}" --project="${P}" --zone="$1" \
      --machine-type="$2" --subnet=fondaria-probe --no-address \
      --no-service-account --no-scopes --boot-disk-type=pd-standard --boot-disk-size=10GB \
      --image-family=debian-12 --image-project=debian-cloud --shielded-secure-boot \
      --labels=purpose=capacity-probe --quiet 2>&1)"; then
    gcloud compute instances delete "${name}" --project="${P}" --zone="$1" --quiet >/dev/null 2>&1 || true
    return 0
  fi
  case "${out}" in
    *RESOURCE_POOL_EXHAUSTED*|*"does not have enough resources"*) log "  $1: no $2 capacity" ;;
    *) log "  $1: probe failed: $(echo "${out}" | tail -1)" ;;
  esac
  return 1
}

# The VPC first: the probes need a subnet in the region they test.
if ! gcloud compute networks describe fondaria --project="${P}" >/dev/null 2>&1; then
  log "creating the VPC (custom subnets, no firewall rules: nothing can reach a node from outside)"
  gcloud compute networks create fondaria --project="${P}" --subnet-mode=custom >/dev/null
fi

if [ -z "${GKE_REGION}" ] || [ -z "${GKE_NODE_ZONES}" ] || [ -z "${GKE_MACHINE}" ]; then
  GKE_REGION="" GKE_NODE_ZONES="" GKE_MACHINE=""
  shortfalls=""
  for region in ${GKE_REGIONS}; do
    for family in ${GKE_MACHINE_FAMILIES}; do
      machine="${family}-standard-${GKE_NODE_VCPUS}"
      short="$(quota_shortfalls "${region}" "${family}")"
      if [ -n "${short}" ]; then
        log "${region} ${machine}: quota too low"
        shortfalls="${shortfalls}${short}
"
        continue
      fi
      log "${region} ${machine}: quotas fit; testing capacity zone by zone"
      gcloud compute networks subnets create fondaria-probe --project="${P}" --region="${region}" \
        --network=fondaria --range=10.255.255.0/28 >/dev/null 2>&1 || true
      found=""
      count=0
      for zone in $(gcloud compute machine-types list --project="${P}" \
          --filter="name=${machine} AND zone~^${region}-" --format='value(zone)' | sort); do
        if probe_zone "${zone}" "${machine}"; then
          found="${found:+${found},}${zone}"
          count=$((count + 1))
          [ "${count}" -eq 3 ] && break
        fi
      done
      gcloud compute networks subnets delete fondaria-probe --project="${P}" --region="${region}" \
        --quiet >/dev/null 2>&1 || true
      if [ "${count}" -eq 3 ]; then
        GKE_REGION="${region}" GKE_NODE_ZONES="${found}" GKE_MACHINE="${machine}"
        break 2
      fi
      log "${region} ${machine}: only ${count} zone(s) with capacity, need 3"
    done
  done
  if [ -z "${GKE_MACHINE}" ]; then
    echo "gke-up: no region in '${GKE_REGIONS}' has the quota and capacity for this cluster." >&2
    if [ -n "${shortfalls}" ]; then
      echo "gke-up: quota shortfalls (raising them for ONE region is enough):" >&2
      printf '%s' "${shortfalls}" | sort -u | sed 's/^/  /' >&2
      echo "gke-up: request at https://console.cloud.google.com/iam-admin/quotas?project=${P}" >&2
      echo "gke-up: approval takes minutes to a business day; the project is kept, so re-run gke-up after" >&2
    fi
    exit 1
  fi
fi
remember GKE_PROFILE "${GKE_PROFILE}"
remember GKE_REGION "${GKE_REGION}"
remember GKE_NODE_ZONES "${GKE_NODE_ZONES}"
remember GKE_MACHINE "${GKE_MACHINE}"
GKE_ZONE="${GKE_NODE_ZONES%%,*}"
remember GKE_ZONE "${GKE_ZONE}"
CONTEXT="gke_${P}_${GKE_ZONE}_${GKE_CLUSTER}"
log "placement: ${GKE_MACHINE} in ${GKE_NODE_ZONES} (control plane in ${GKE_ZONE})"

# ---------------------------------------------------------------- network ---
# A subnet left in another region by an earlier placement would overlap this
# one's ranges; with no cluster on it, it goes.
old_region="$(gcloud compute networks subnets list --project="${P}" --network=fondaria \
  --filter="name=fondaria" --format='value(region.basename())')"
if [ -n "${old_region}" ] && [ "${old_region}" != "${GKE_REGION}" ]; then
  log "moving the subnet from ${old_region} to ${GKE_REGION}"
  gcloud compute routers delete fondaria --region="${old_region}" --project="${P}" --quiet >/dev/null 2>&1 || true
  gcloud compute networks subnets delete fondaria --region="${old_region}" --project="${P}" --quiet >/dev/null
fi
if ! gcloud compute networks subnets describe fondaria --region="${GKE_REGION}" --project="${P}" >/dev/null 2>&1; then
  gcloud compute networks subnets create fondaria --project="${P}" --region="${GKE_REGION}" \
    --network=fondaria --range="${GKE_SUBNET_RANGE}" --enable-private-ip-google-access \
    --secondary-range="pods=${GKE_PODS_RANGE},services=${GKE_SERVICES_RANGE}" >/dev/null
fi
if ! gcloud compute routers describe fondaria --region="${GKE_REGION}" --project="${P}" >/dev/null 2>&1; then
  log "creating Cloud NAT (outbound only: image pulls)"
  gcloud compute routers create fondaria --project="${P}" --region="${GKE_REGION}" --network=fondaria >/dev/null
  gcloud compute routers nats create fondaria --project="${P}" --region="${GKE_REGION}" --router=fondaria \
    --auto-allocate-nat-external-ips --nat-all-subnet-ip-ranges >/dev/null
fi
# A private cluster's control plane reaches nodes only on 443 and 10250 by
# default. Admission webhooks listen elsewhere: Linkerd's proxy injector and
# SP validator (8443), Linkerd's policy controller, CloudNativePG and Envoy
# Gateway (9443). Without this rule every pod creation hangs at admission.
if ! gcloud compute firewall-rules describe fondaria-allow-webhooks --project="${P}" >/dev/null 2>&1; then
  gcloud compute firewall-rules create fondaria-allow-webhooks --project="${P}" --network=fondaria \
    --direction=INGRESS --source-ranges="${GKE_MASTER_RANGE}" --allow=tcp:8443,tcp:9443 >/dev/null
fi

# ------------------------------------------- node identity and registry ---
nodes_sa="gke-nodes@${P}.iam.gserviceaccount.com"
if ! gcloud iam service-accounts describe "${nodes_sa}" --project="${P}" >/dev/null 2>&1; then
  gcloud iam service-accounts create gke-nodes --project="${P}" \
    --display-name="GKE nodes: pull images, nothing else"
fi
retry gcloud projects add-iam-policy-binding "${P}" --condition=None --quiet \
  --member="serviceAccount:${nodes_sa}" --role=roles/container.defaultNodeServiceAccount
if ! gcloud artifacts repositories describe fondaria --location="${GKE_REGION}" --project="${P}" >/dev/null 2>&1; then
  gcloud artifacts repositories create fondaria --project="${P}" --location="${GKE_REGION}" \
    --repository-format=docker >/dev/null
fi
retry gcloud artifacts repositories add-iam-policy-binding fondaria --project="${P}" --location="${GKE_REGION}" \
  --member="serviceAccount:${nodes_sa}" --role=roles/artifactregistry.reader

# ---------------------------------------------------------------- cluster ---
admin_ip="${GKE_ADMIN_IP:-$(curl -4 -fsS --max-time 10 https://api.ipify.org)}"
[ -n "${admin_ip}" ] || die "could not detect this machine's public IPv4; set GKE_ADMIN_IP"

if [ "${GKE_STATEFUL_NODES_PER_ZONE}" -gt 0 ]; then
  default_pool_label=stateless
else
  default_pool_label=shared
fi
# With the quota exactly used up (small profile), a surge upgrade could not
# add its extra node: upgrade one node at a time in place instead.
upgrade_flags=""
[ "${GKE_PROFILE}" = small ] && upgrade_flags="--max-surge-upgrade=0 --max-unavailable-upgrade=1"
node_flags="${upgrade_flags} --machine-type=${GKE_MACHINE} --service-account=${nodes_sa} --workload-metadata=GKE_METADATA \
  --shielded-secure-boot --shielded-integrity-monitoring \
  --disk-type=${GKE_BOOT_DISK_TYPE} --disk-size=${GKE_BOOT_DISK_GB}"

if ! gcloud container clusters describe "${GKE_CLUSTER}" --zone="${GKE_ZONE}" --project="${P}" >/dev/null 2>&1; then
  log "creating cluster ${GKE_CLUSTER} (~10-20 min); control plane open to ${admin_ip}/32 only"
  # --quiet accepts GKE's notice that support is best-effort without Cloud
  # Logging: logs stay in the cluster's own Loki (ADR-0036).
  # shellcheck disable=SC2086
  gcloud container clusters create "${GKE_CLUSTER}" --project="${P}" --zone="${GKE_ZONE}" --quiet \
    --node-locations="${GKE_NODE_ZONES}" --release-channel="${GKE_RELEASE_CHANNEL}" \
    --network=fondaria --subnetwork=fondaria --enable-ip-alias \
    --cluster-secondary-range-name=pods --services-secondary-range-name=services \
    --enable-private-nodes --master-ipv4-cidr="${GKE_MASTER_RANGE}" \
    --enable-master-authorized-networks --master-authorized-networks="${admin_ip}/32" \
    --enable-dataplane-v2 --in-transit-encryption=inter-node-transparent \
    --workload-pool="${P}.svc.id.goog" \
    --logging=NONE --monitoring=NONE --no-enable-managed-prometheus \
    --num-nodes="${GKE_STATELESS_NODES_PER_ZONE}" \
    --node-labels="fondaria.example/node-pool=${default_pool_label}" \
    ${node_flags}
else
  # The machine's IP can change between runs (new network, router reboot).
  gcloud container clusters update "${GKE_CLUSTER}" --project="${P}" --zone="${GKE_ZONE}" \
    --enable-master-authorized-networks --master-authorized-networks="${admin_ip}/32" >/dev/null
fi

if [ "${GKE_STATEFUL_NODES_PER_ZONE}" -gt 0 ] \
    && ! gcloud container node-pools describe stateful --cluster="${GKE_CLUSTER}" --zone="${GKE_ZONE}" \
    --project="${P}" >/dev/null 2>&1; then
  log "creating the stateful node pool (tainted: only the datastores, which tolerate it, land here)"
  # shellcheck disable=SC2086
  gcloud container node-pools create stateful --project="${P}" --zone="${GKE_ZONE}" \
    --cluster="${GKE_CLUSTER}" --node-locations="${GKE_NODE_ZONES}" \
    --num-nodes="${GKE_STATEFUL_NODES_PER_ZONE}" \
    --node-labels=fondaria.example/node-pool=stateful \
    --node-taints=fondaria.example/node-pool=stateful:NoSchedule \
    ${node_flags}
fi

gcloud container clusters get-credentials "${GKE_CLUSTER}" --zone="${GKE_ZONE}" --project="${P}"
remember GKE_CONTEXT "${CONTEXT}"
log "cluster version: $(gcloud container clusters describe "${GKE_CLUSTER}" --zone="${GKE_ZONE}" \
  --project="${P}" --format='value(currentMasterVersion)')"

# -------------------------------------------------------------- operators ---
# Same pinned upstream manifests as the kind bootstrap (bootstrap/versions.env).
# Not installed here: Calico and its WireGuard config (Dataplane V2 enforces
# NetworkPolicy and encrypts between nodes) and metrics-server (GKE runs its own).
K="kubectl --context=${CONTEXT}"
"${here}/secrets.sh"
generated="${here}/../k8s/overlays/gke/generated"

apply_pinned() {
  log "applying $1"
  ${K} apply --server-side --force-conflicts -f "$2" >/dev/null
}
${K} get crd clusters.postgresql.cnpg.io >/dev/null 2>&1 \
  || apply_pinned "CloudNativePG ${CNPG_VERSION}" "${CNPG_MANIFEST}"
${K} get crd rabbitmqclusters.rabbitmq.com >/dev/null 2>&1 \
  || apply_pinned "RabbitMQ cluster operator ${RABBITMQ_OPERATOR_VERSION}" "${RABBITMQ_OPERATOR_MANIFEST}"
${K} get crd gatewayclasses.gateway.networking.k8s.io >/dev/null 2>&1 \
  || apply_pinned "Envoy Gateway ${ENVOY_GATEWAY_VERSION}" "${ENVOY_GATEWAY_MANIFEST}"

log "waiting for operators"
${K} -n cnpg-system rollout status deployment/cnpg-controller-manager --timeout=300s
${K} -n rabbitmq-system rollout status deployment/rabbitmq-cluster-operator --timeout=300s
${K} -n envoy-gateway-system rollout status deployment/envoy-gateway --timeout=300s

apply_pinned "Envoy Gateway rate-limit backend" "${here}/../k8s/bootstrap/envoy-gateway-config.yaml"
${K} -n envoy-gateway-system rollout restart deployment/envoy-gateway
${K} -n envoy-gateway-system rollout status deployment/envoy-gateway --timeout=300s

if ! ${K} get crd servers.policy.linkerd.io >/dev/null 2>&1; then
  log "installing Linkerd CRDs"
  linkerd --context="${CONTEXT}" install --crds | ${K} apply --server-side --force-conflicts -f - >/dev/null
fi
if ! ${K} get ns linkerd >/dev/null 2>&1; then
  log "installing Linkerd control plane"
  linkerd --context="${CONTEXT}" install \
    --identity-trust-anchors-file "${generated}/linkerd-ca.crt" \
    --identity-issuer-certificate-file "${generated}/linkerd-issuer.crt" \
    --identity-issuer-key-file "${generated}/linkerd-issuer.key" \
    --default-inbound-policy=deny \
    | ${K} apply --server-side --force-conflicts -f - >/dev/null
fi
${K} -n linkerd rollout status deployment/linkerd-identity --timeout=300s
${K} -n linkerd rollout status deployment/linkerd-destination --timeout=300s
${K} -n linkerd rollout status deployment/linkerd-proxy-injector --timeout=300s

${K} get namespace fondaria >/dev/null 2>&1 || ${K} create namespace fondaria
kubectl config set-context "${CONTEXT}" --namespace=fondaria >/dev/null

log "ready. Project ${P}, context ${CONTEXT}."
log "it deletes itself at ${expires_at} even if you do nothing (every gke-up re-arms it)."
log "next: make gke-deploy"
