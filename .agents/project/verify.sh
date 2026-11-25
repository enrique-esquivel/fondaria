#!/usr/bin/env bash
# Fondaria's gates, run by `harness verify` in the issue's worktree. Usage: verify.sh <issue> <phase> <base>
# CI runs `make verify` on a PR and the integration suite only twice a week, so the lane runs the whole suite here.
set -euo pipefail
issue="$1"; phase="$2"; base="$3"
cd "$(git rev-parse --show-toplevel)"
say() { echo "[verify] $*" >&2; }
fail() { echo "[verify] FAILED: $*" >&2; exit 1; }

# 1. A merged migration is never edited (docs/ENGINEERING-RULES.md §4).
bad="$(git diff --name-only --no-renames --diff-filter=MD "$base" HEAD -- libs/db-migrations/src/main/resources/db/migration)"
[ -z "$bad" ] || fail "merged migrations changed: $bad"

# 2. No new dependency unless the task names it: the issue's meta block carries `dependencies: allowed`.
if git diff "$base" HEAD -- '*pom.xml' | grep -qE '^\+[[:space:]]*<dependency>'; then
  [ "$phase" = stack ] || gh issue view "$issue" --json body -q .body | grep -q '^dependencies: allowed' \
    || fail "a <dependency> was added, and issue #$issue does not say 'dependencies: allowed'"
fi

# 3. Never floating point for money: double/float/BigDecimal added in production code needs a look at review.
git diff "$base" HEAD -- 'libs/**/src/main/*.java' 'services/**/src/main/*.java' \
  | grep -E '^\+(.*[^A-Za-z])?(double|float|BigDecimal|Double|Float)[^A-Za-z]' >&2 \
  && say "WARNING: floating point or BigDecimal added (above): allowed only at the display boundary" || true

changed="$(git diff --name-only "$base" HEAD)"
[ -n "$changed" ] || fail "no committed change to verify"
if ! grep -qvE '^(docs/|\.agents/)|\.md$' <<<"$changed"; then say "docs or lane adapter only: no build"; exit 0; fi

# 4. One build at a time on this machine, and not next to the kind cluster or another project's stack: they
#    share the Docker VM, starve it and cause false timing failures. Wait for them; never stop them from here.
lock="${HARNESS_HOME:?}/verify.lock"; mkdir -p "$HARNESS_HOME"
deadline=$(( $(date +%s) + ${VERIFY_WAIT_MIN:-90} * 60 ))
until mkdir "$lock" 2>/dev/null; do
  pid="$(cat "$lock/pid" 2>/dev/null || true)"
  if [ -n "$pid" ] && ! ps -p "$pid" >/dev/null 2>&1; then rm -rf "$lock"; continue; fi
  [ "$(date +%s)" -lt "$deadline" ] || fail "another verify has held $lock for too long (pid ${pid:-unknown})"
  sleep 10
done
echo $$ > "$lock/pid"; trap 'rm -rf "$lock"' EXIT
busy() { docker ps --format '{{.Names}}' | grep -E "${VERIFY_EXCLUSIVE_RE:-^(aura-|fondaria-control-plane)}" | head -3; }
while b="$(busy)" && [ -n "$b" ]; do
  [ "$(date +%s)" -lt "$deadline" ] || fail "these containers still share the Docker VM: $(tr '\n' ' ' <<<"$b")"
  say "waiting for: $(tr '\n' ' ' <<<"$b")"; sleep 60
done

# 5. CI's own steps. `install` first: JibBuildSimulationIntegrationTest resolves siblings from ~/.m2 (ci.yml).
mvn -q install -DskipTests -Dspotless.check.skip=true
make verify-all
if grep -qE '^infrastructure/k8s/' <<<"$changed"; then make verify-k8s; fi
say "ok"
