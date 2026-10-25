#!/bin/sh
# Generates the GKE load-test overlay's credentials into
# infrastructure/k8s/overlays/gke/generated/ (gitignored), never committed:
# the gateway TLS pair and Linkerd trust anchor + issuer (via the same
# bootstrap/local-secrets.sh the kind cluster uses), plus one env file per
# Secret that overlays/gke/kustomization.yaml's secretGenerator reads.
#
# Unlike the local overlay's fixed demo literals, every value here is random:
# this cluster runs on a public cloud. Nothing is reachable from the internet
# (no LoadBalancer, private nodes), but a credential that is also printed in a
# public repository is not one.
#
# Idempotent: an existing file is left alone, so re-running never rotates a
# credential under a running cluster. Called by `make local-secrets` (so
# `make verify` can render the overlay offline) and by up.sh.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
out="${here}/../k8s/overlays/gke/generated"

command -v openssl >/dev/null 2>&1 || { echo "gke-secrets: openssl is not installed" >&2; exit 1; }

"${here}/../k8s/bootstrap/local-secrets.sh" "${out}" >/dev/null

umask 077

random() { openssl rand -hex 24; }

# $1 = file name, remaining args = KEY lines generated when the file is absent.
write_env() {
  file="${out}/$1.env"
  shift
  if [ ! -s "${file}" ]; then
    : > "${file}"
    for line in "$@"; do
      printf '%s\n' "${line}" >> "${file}"
    done
  fi
}

write_env object-store-credentials \
  "OBJECT_STORE_ROOT_ACCESS_KEY=fondaria$(openssl rand -hex 4)" \
  "OBJECT_STORE_ROOT_SECRET_KEY=$(random)"
write_env grafana-admin-secret "admin-user=admin" "admin-password=$(random)"
write_env balance-api-secret "QUERY_CURSOR_SECRET=$(random)"
# 32 random bytes, base64 -- the same shape as the local overlay's demo key.
write_env contribution-api-secret "PARTICIPANT_IDENTITY_MASTER_KEY=$(openssl rand -base64 32)"
write_env valkey-credentials "password=$(random)"
write_env keycloak-admin-secret "admin-user=admin" "admin-password=$(random)"
write_env keycloak-client-secrets \
  "employer-portal=$(random)" \
  "internal-services=$(random)"

echo "gke-secrets: credentials present in overlays/gke/generated/"
