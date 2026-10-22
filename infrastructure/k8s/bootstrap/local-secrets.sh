#!/bin/sh
# Generates the local kind cluster's private keys and certificates, which are
# never committed: the gateway's self-signed TLS pair and Linkerd's trust
# anchor + identity issuer. They land in overlays/local/generated/ (gitignored),
# where overlays/local/kustomization.yaml's secretGenerator and up.sh's
# `linkerd install` read them.
#
# Idempotent: a pair already on disk is left alone, so re-running never
# rotates a key under a running cluster. Each pair is generated as a unit --
# if any file of a pair is missing, the whole pair is regenerated, so the
# Linkerd issuer is always signed by the trust anchor sitting next to it.
#
# Local only. Production's gateway certificate comes from cert-manager
# (overlays/prod/certificates.yaml), and its Linkerd control plane, with its
# own trust anchor and rotation, is assumed to exist before overlays/prod is
# applied.
#
# Called by `make k8s-up` (via up.sh), `make skaffold-run`/`skaffold-dev` and
# `make verify` (the validation tests render overlays/local). Run it by hand
# with `make local-secrets`.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
out="${here}/../overlays/local/generated"

command -v openssl >/dev/null 2>&1 || { echo "local-secrets: openssl is not installed" >&2; exit 1; }

mkdir -p "${out}"
umask 077

work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

# 1. fondaria-gateway's local listeners (api.fondaria.local, auth.fondaria.local).
if [ ! -s "${out}/gateway-tls.crt" ] || [ ! -s "${out}/gateway-tls.key" ]; then
  echo "local-secrets: generating the gateway's self-signed TLS pair"
  openssl req -x509 -nodes -newkey rsa:2048 \
    -keyout "${out}/gateway-tls.key" -out "${out}/gateway-tls.crt" -days 3650 \
    -subj "/CN=api.fondaria.local" \
    -addext "subjectAltName=DNS:api.fondaria.local,DNS:auth.fondaria.local" \
    -addext "keyUsage=keyEncipherment,digitalSignature" \
    -addext "extendedKeyUsage=serverAuth" 2>/dev/null
fi

# 2. Linkerd's mTLS trust anchor (root CA) and identity issuer (intermediate
# CA signed by that root). Linkerd requires ECDSA P-256, and both must be
# CA:TRUE -- the issuer signs per-pod leaf certificates at runtime. The root
# CA's private key is discarded: `linkerd install` needs only the root
# certificate plus the issuer's certificate and key.
if [ ! -s "${out}/linkerd-ca.crt" ] || [ ! -s "${out}/linkerd-issuer.crt" ] \
  || [ ! -s "${out}/linkerd-issuer.key" ]; then
  echo "local-secrets: generating the Linkerd trust anchor and identity issuer"
  openssl ecparam -name prime256v1 -genkey -noout -out "${work}/ca.key"
  openssl req -new -x509 -key "${work}/ca.key" -out "${out}/linkerd-ca.crt" -days 3650 \
    -subj "/CN=root.linkerd.cluster.local" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"

  openssl ecparam -name prime256v1 -genkey -noout -out "${out}/linkerd-issuer.key"
  openssl req -new -key "${out}/linkerd-issuer.key" -out "${work}/issuer.csr" \
    -subj "/CN=identity.linkerd.cluster.local"
  printf 'basicConstraints=critical,CA:TRUE,pathlen:0\nkeyUsage=critical,keyCertSign,cRLSign\n' \
    > "${work}/issuer.ext"
  openssl x509 -req -in "${work}/issuer.csr" -CA "${out}/linkerd-ca.crt" \
    -CAkey "${work}/ca.key" -CAcreateserial -CAserial "${work}/ca.srl" \
    -out "${out}/linkerd-issuer.crt" -days 3650 -extfile "${work}/issuer.ext" 2>/dev/null
fi

echo "local-secrets: keys and certificates present in overlays/local/generated/"
