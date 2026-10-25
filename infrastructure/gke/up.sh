#!/bin/sh
# Creates the throwaway GKE load-test environment (ADR-0036) in a brand-new
# Google Cloud project, then installs the operators the platform's manifests
# assume. Deploy the platform afterwards with `make gke-deploy`.
#
# Security posture, in the order it is built:
#   1. A new project per run. Only the APIs below are enabled -- no Vertex AI,
#      no Gemini/Generative Language API, no API keys are ever created.
#   2. A dead man's switch BEFORE anything billable: a Cloud Scheduler job
#      deletes the whole project GKE_TTL_HOURS after this script starts, run by
#      a service account that can do nothing else (projectDeleter on this one
#      project).
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
if [ -s "${state}" ]; then
  # shellcheck disable=SC1090
  . "${state}"
fi
if [ -z "${GKE_PROJECT:-}" ]; then
  GKE_PROJECT="${GKE_PROJECT_PREFIX}-$(openssl rand -hex 3)"
  GKE_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'GKE_PROJECT=%s\nGKE_STARTED_AT=%s\n' "${GKE_PROJECT}" "${GKE_STARTED_AT}" > "${state}"
fi
P="${GKE_PROJECT}"
CONTEXT="gke_${P}_${GKE_ZONE}_${GKE_CLUSTER}"

if ! gcloud projects describe "${P}" >/dev/null 2>&1; then
  log "creating project ${P}"
  gcloud projects create "${P}" --name="fondaria load test" --labels=purpose=load-test,owner=fondaria
fi
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
  billingbudgets.googleapis.com

# ------------------------------------------------------ dead man's switch ---
# Deletes the PROJECT, not just the cluster: a deleted cluster leaves its
# persistent disks, NAT and registry behind, still billing.
deadman="deadman@${P}.iam.gserviceaccount.com"
if ! gcloud iam service-accounts describe "${deadman}" --project="${P}" >/dev/null 2>&1; then
  gcloud iam service-accounts create deadman --project="${P}" \
    --display-name="Deletes this project at its TTL; nothing else"
fi
gcloud projects add-iam-policy-binding "${P}" --condition=None --quiet \
  --member="serviceAccount:${deadman}" --role=roles/resourcemanager.projectDeleter >/dev/null
if ! gcloud scheduler jobs describe fondaria-ttl --location="${GKE_REGION}" --project="${P}" >/dev/null 2>&1; then
  # A five-field cron for one moment: minute hour day month (any weekday).
  cron="$(python3 -c "
import datetime as d
t = d.datetime.strptime('${GKE_STARTED_AT}', '%Y-%m-%dT%H:%M:%SZ') + d.timedelta(hours=${GKE_TTL_HOURS})
print(f'{t.minute} {t.hour} {t.day} {t.month} *')")"
  log "dead man's switch: project deletes itself at '${cron}' UTC (${GKE_TTL_HOURS} h after ${GKE_STARTED_AT})"
  gcloud scheduler jobs create http fondaria-ttl --project="${P}" --location="${GKE_REGION}" \
    --schedule="${cron}" --time-zone="Etc/UTC" \
    --uri="https://cloudresourcemanager.googleapis.com/v3/projects/${P}" --http-method=DELETE \
    --oauth-service-account-email="${deadman}" \
    --oauth-token-scope="https://www.googleapis.com/auth/cloud-platform" \
    --attempt-deadline=60s --max-retry-attempts=5 >/dev/null
fi

# ------------------------------------------------------------ budget alert ---
if ! gcloud billing budgets list --billing-account="${GKE_BILLING_ACCOUNT}" --billing-project="${P}" \
    --format='value(displayName)' 2>/dev/null | grep -qx "fondaria-load-test-guard"; then
  log "creating a ${GKE_BUDGET_USD} USD budget alert on the whole billing account"
  gcloud billing budgets create --billing-account="${GKE_BILLING_ACCOUNT}" --billing-project="${P}" \
    --display-name="fondaria-load-test-guard" --budget-amount="${GKE_BUDGET_USD}USD" \
    --threshold-rule=percent=0.25 --threshold-rule=percent=0.5 \
    --threshold-rule=percent=0.9 --threshold-rule=percent=1.0 >/dev/null
fi

# ------------------------------------------------------------------ quota ---
zones=$(echo "${GKE_NODE_ZONES}" | tr ',' '\n' | wc -l | tr -d ' ')
vcpus() { echo "${1##*-}"; }
need_cpus=$(( (GKE_STATEFUL_NODES_PER_ZONE * $(vcpus "${GKE_STATEFUL_MACHINE}") \
  + GKE_STATELESS_NODES_PER_ZONE * $(vcpus "${GKE_STATELESS_MACHINE}")) * zones ))
gcloud compute regions describe "${GKE_REGION}" --project="${P}" --format=json \
  | python3 -c "
import json, sys
need = ${need_cpus}
q = {x['metric']: x['limit'] for x in json.load(sys.stdin)['quotas']}
short = [f'{m} limit {int(q.get(m, 0))} < {need}' for m in ('CPUS', 'N2_CPUS') if q.get(m, 0) < need]
if short:
    print('gke-up: CPU quota too low in ${GKE_REGION}: ' + '; '.join(short), file=sys.stderr)
    print('gke-up: request an increase to at least', need, 'at', file=sys.stderr)
    print('  https://console.cloud.google.com/iam-admin/quotas?project=${P}', file=sys.stderr)
    print('gke-up: then re-run make gke-up (it resumes where it stopped)', file=sys.stderr)
    sys.exit(1)
" || exit 1

# ---------------------------------------------------------------- network ---
if ! gcloud compute networks describe fondaria --project="${P}" >/dev/null 2>&1; then
  log "creating the VPC (custom subnets, no firewall rules: nothing can reach a node from outside)"
  gcloud compute networks create fondaria --project="${P}" --subnet-mode=custom >/dev/null
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
gcloud projects add-iam-policy-binding "${P}" --condition=None --quiet \
  --member="serviceAccount:${nodes_sa}" --role=roles/container.defaultNodeServiceAccount >/dev/null
if ! gcloud artifacts repositories describe fondaria --location="${GKE_REGION}" --project="${P}" >/dev/null 2>&1; then
  gcloud artifacts repositories create fondaria --project="${P}" --location="${GKE_REGION}" \
    --repository-format=docker >/dev/null
fi
gcloud artifacts repositories add-iam-policy-binding fondaria --project="${P}" --location="${GKE_REGION}" \
  --member="serviceAccount:${nodes_sa}" --role=roles/artifactregistry.reader >/dev/null

# ---------------------------------------------------------------- cluster ---
admin_ip="${GKE_ADMIN_IP:-$(curl -4 -fsS --max-time 10 https://api.ipify.org)}"
[ -n "${admin_ip}" ] || die "could not detect this machine's public IPv4; set GKE_ADMIN_IP"

node_flags="--service-account=${nodes_sa} --workload-metadata=GKE_METADATA \
  --shielded-secure-boot --shielded-integrity-monitoring \
  --disk-type=pd-balanced --disk-size=100"

if ! gcloud container clusters describe "${GKE_CLUSTER}" --zone="${GKE_ZONE}" --project="${P}" >/dev/null 2>&1; then
  log "creating cluster ${GKE_CLUSTER} (~10 min); control plane open to ${admin_ip}/32 only"
  # shellcheck disable=SC2086
  gcloud container clusters create "${GKE_CLUSTER}" --project="${P}" --zone="${GKE_ZONE}" \
    --node-locations="${GKE_NODE_ZONES}" --release-channel="${GKE_RELEASE_CHANNEL}" \
    --network=fondaria --subnetwork=fondaria --enable-ip-alias \
    --cluster-secondary-range-name=pods --services-secondary-range-name=services \
    --enable-private-nodes --master-ipv4-cidr="${GKE_MASTER_RANGE}" \
    --enable-master-authorized-networks --master-authorized-networks="${admin_ip}/32" \
    --enable-dataplane-v2 --in-transit-encryption=inter-node-transparent \
    --workload-pool="${P}.svc.id.goog" \
    --logging=NONE --monitoring=NONE --no-enable-managed-prometheus \
    --machine-type="${GKE_STATELESS_MACHINE}" --num-nodes="${GKE_STATELESS_NODES_PER_ZONE}" \
    --node-labels=fondaria.example/node-pool=stateless \
    ${node_flags}
else
  # The machine's IP can change between runs (new network, router reboot).
  gcloud container clusters update "${GKE_CLUSTER}" --project="${P}" --zone="${GKE_ZONE}" \
    --enable-master-authorized-networks --master-authorized-networks="${admin_ip}/32" >/dev/null
fi

if ! gcloud container node-pools describe stateful --cluster="${GKE_CLUSTER}" --zone="${GKE_ZONE}" \
    --project="${P}" >/dev/null 2>&1; then
  log "creating the stateful node pool (tainted: only the datastores, which tolerate it, land here)"
  # shellcheck disable=SC2086
  gcloud container node-pools create stateful --project="${P}" --zone="${GKE_ZONE}" \
    --cluster="${GKE_CLUSTER}" --node-locations="${GKE_NODE_ZONES}" \
    --machine-type="${GKE_STATEFUL_MACHINE}" --num-nodes="${GKE_STATEFUL_NODES_PER_ZONE}" \
    --node-labels=fondaria.example/node-pool=stateful \
    --node-taints=fondaria.example/node-pool=stateful:NoSchedule \
    ${node_flags}
fi

gcloud container clusters get-credentials "${GKE_CLUSTER}" --zone="${GKE_ZONE}" --project="${P}"
grep -q '^GKE_CONTEXT=' "${state}" || printf 'GKE_CONTEXT=%s\n' "${CONTEXT}" >> "${state}"
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
log "it deletes itself ${GKE_TTL_HOURS} h after ${GKE_STARTED_AT} even if you do nothing."
log "next: make gke-deploy"
