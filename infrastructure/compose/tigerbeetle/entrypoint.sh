#!/bin/sh
set -e

DATA_FILE="${TIGERBEETLE_DATA_FILE:-/var/lib/tigerbeetle/0_0.tigerbeetle}"
CLUSTER_ID="${TIGERBEETLE_CLUSTER_ID:-0}"
REPLICA_ID="${TIGERBEETLE_REPLICA_ID:-0}"

mkdir -p "$(dirname "$DATA_FILE")"

if [ ! -f "$DATA_FILE" ]; then
  echo "Data file $DATA_FILE does not exist. Formatting TigerBeetle replica..."
  /tigerbeetle format --cluster="$CLUSTER_ID" --replica="$REPLICA_ID" --replica-count=1 "$DATA_FILE"
else
  echo "Data file $DATA_FILE exists. Skipping format."
fi

echo "Starting TigerBeetle..."
# No --development (row 26q, ADR-0033, 2026-10-03 amendment): it cut the request limit to 32KiB, the
# replica to 8 clients and every cache to its minimum, so local runs did not behave
# like production. Without it the replica requires Direct IO, which Docker Desktop's
# bind mount (virtiofs) supports; a file-sharing mode that does not will refuse to
# format or start, and the fix is a named volume, not --development. A data file
# formatted with --development starts without it unchanged. The grid cache (default 1GiB) is shrunk to fit the
# container's memory limit next to the JVMs, as on the kind overlay.
exec /tigerbeetle start --addresses=0.0.0.0:3000 --cache-grid=512MiB "$DATA_FILE"
