# Shared by up.sh and stateful-pool.sh. Callers define log() and die() and set
# state (the path of .state) before sourcing this file.

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

# Records KEY=value in .state, replacing an earlier value.
remember() {
  touch "${state}"
  grep -v "^$1=" "${state}" > "${state}.tmp" || true
  printf '%s=%s\n' "$1" "$2" >> "${state}.tmp"
  mv "${state}.tmp" "${state}"
}

# ------------------------------------------------------ dead man's switch ---
# Ends the PROJECT's spending, not just the cluster's: a deleted cluster leaves
# its persistent disks, NAT and registry behind, still billing. A project
# up.sh created is deleted; a kept one (GKE_PROJECT) has its billing unlinked,
# which stops every charge at once and deletes nothing -- the next gke-up links
# it again. Arms (or re-arms) the switch $1 minutes from now. Needs P,
# GKE_KEEP_PROJECT and GKE_HOME_REGION; sets GKE_EXPIRES_AT and deadman_does_log.
arm_deadman() {
  ttl_minutes="$1"
  deadman="deadman@${P}.iam.gserviceaccount.com"
  if [ "${GKE_KEEP_PROJECT}" = true ]; then
    deadman_role=roles/billing.projectManager
    other_role=roles/resourcemanager.projectDeleter
    deadman_does="Unlinks this project's billing at its TTL; nothing else"
    deadman_does_log="unlinks project ${P}'s billing"
    set -- --uri="https://cloudbilling.googleapis.com/v1/projects/${P}/billingInfo" --http-method=PUT \
      --message-body='{"billingAccountName":""}'
    headers=Content-Type=application/json
    update_extra="--update-headers=${headers}"
  else
    deadman_role=roles/resourcemanager.projectDeleter
    other_role=roles/billing.projectManager
    deadman_does="Deletes this project at its TTL; nothing else"
    deadman_does_log="deletes project ${P}"
    set -- --uri="https://cloudresourcemanager.googleapis.com/v3/projects/${P}" --http-method=DELETE
    headers=""
    update_extra="--clear-headers --clear-message-body"
  fi
  if ! gcloud iam service-accounts describe "${deadman}" --project="${P}" >/dev/null 2>&1; then
    gcloud iam service-accounts create deadman --project="${P}" --display-name="${deadman_does}"
  fi
  retry gcloud projects add-iam-policy-binding "${P}" --condition=None --quiet \
    --member="serviceAccount:${deadman}" --role="${deadman_role}"
  # The switch holds exactly one power: drop the other mode's role if an earlier
  # run granted it.
  gcloud projects remove-iam-policy-binding "${P}" --condition=None --quiet \
    --member="serviceAccount:${deadman}" --role="${other_role}" >/dev/null 2>&1 || true
  # Cron has one-minute resolution, so the switch fires in the minute after.
  expires="$(TTL="${ttl_minutes}" python3 -c '
import datetime as d, os
t = d.datetime.now(d.timezone.utc).replace(second=0, microsecond=0) + d.timedelta(minutes=int(os.environ["TTL"]) + 1)
print(t.strftime("%Y-%m-%dT%H:%MZ"), f"{t.minute} {t.hour} {t.day} {t.month} *")')"
  GKE_EXPIRES_AT="${expires%% *}"
  cron="${expires#* }"
  if gcloud scheduler jobs describe fondaria-ttl --location="${GKE_HOME_REGION}" --project="${P}" >/dev/null 2>&1; then
    # The target too, not just the time: what the switch does follows the mode.
    # shellcheck disable=SC2086
    gcloud scheduler jobs update http fondaria-ttl --project="${P}" --location="${GKE_HOME_REGION}" \
      --schedule="${cron}" --time-zone="Etc/UTC" "$@" ${update_extra} >/dev/null
    gcloud scheduler jobs resume fondaria-ttl --project="${P}" --location="${GKE_HOME_REGION}" >/dev/null
  else
    retry gcloud scheduler jobs create http fondaria-ttl --project="${P}" --location="${GKE_HOME_REGION}" \
      --schedule="${cron}" --time-zone="Etc/UTC" "$@" ${headers:+--headers="${headers}"} \
      --oauth-service-account-email="${deadman}" \
      --oauth-token-scope="https://www.googleapis.com/auth/cloud-platform" \
      --attempt-deadline=60s --max-retry-attempts=5
  fi
  remember GKE_EXPIRES_AT "${GKE_EXPIRES_AT}"
}
