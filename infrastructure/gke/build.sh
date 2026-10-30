#!/bin/sh
# Builds every service image for linux/amd64 (GKE's n2 nodes; this repo's
# developers build on arm64 Macs) and pushes it to the environment's own
# Artifact Registry, recording what skaffold built in .build-artifacts.json.
#
# up.sh starts it in the background as soon as the registry exists, so the
# images build while the cluster is created; deploy.sh runs it again before
# the stateful pool exists, which is then a no-op unless the source changed.
#
# A build is reused only when .build-artifacts.stamp matches the working tree
# exactly -- the git tree of every tracked and untracked, non-ignored file --
# plus the profile and registry. A new commit, an edit or a new file rebuilds.
set -eu

here="$(cd "$(dirname "$0")" && pwd)"
root="${here}/../.."
# shellcheck disable=SC1091
. "${here}/config.env"
[ -s "${here}/.state" ] || { echo "gke-build: no environment; run make gke-up first" >&2; exit 1; }
# shellcheck disable=SC1091
. "${here}/.state"

log() { echo "gke-build: $*"; }

registry="${GKE_REGION}-docker.pkg.dev"
repo="${registry}/${GKE_PROJECT}/fondaria"
case "${GKE_PROFILE:-full}" in small) profile=gke-small ;; *) profile=gke ;; esac
artifacts="${here}/.build-artifacts.json"
stamp_file="${here}/.build-artifacts.stamp"

# The id of the tree `git add -A` would commit, written through a copy of the
# index so the real one is untouched.
cd "${root}"
index="$(mktemp)"
trap 'rm -f "${index}" "${artifacts}.tmp" "${stamp_file}.tmp"' EXIT
cp "$(git rev-parse --git-path index)" "${index}"
GIT_INDEX_FILE="${index}" git add -A
tree="$(GIT_INDEX_FILE="${index}" git write-tree)"
stamp="tree=${tree} profile=${profile} repo=${repo}"

if [ -s "${artifacts}" ] && [ "$(cat "${stamp_file}" 2>/dev/null)" = "${stamp}" ]; then
  log "images already pushed for this source tree; reusing ${artifacts##*/}"
  exit 0
fi

# Jib pushes through Docker's credential helper for this one registry host.
gcloud auth configure-docker "${registry}" --quiet >/dev/null 2>&1

# The stamp goes first, so a build that dies half-way is never reused.
rm -f "${stamp_file}"
log "building and pushing images ($(date -u +%H:%M:%SZ))"
skaffold build --profile="${profile}" \
  --default-repo="${repo}" \
  --platform=linux/amd64 \
  --file-output="${artifacts}.tmp"
mv "${artifacts}.tmp" "${artifacts}"
printf '%s\n' "${stamp}" > "${stamp_file}.tmp"
mv "${stamp_file}.tmp" "${stamp_file}"
log "images pushed ($(date -u +%H:%M:%SZ))"
