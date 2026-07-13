#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REPO="${REPO:-bspippi1337/blckswan}"
BRANCH="${BRANCH:-agent/monica-key-live}"
WORKFLOW="${WORKFLOW:-monica-key.yml}"
OUT="${OUT:-$HOME/storage/downloads/MonicaKey-debug.apk}"
LOG="${LOG:-$HOME/monica-key-github-build.log}"

exec > >(tee -a "$LOG") 2>&1

step() { printf '\n\033[1;32m[%s] %s\033[0m\n' "$1" "$2"; }
fail() { printf '\n\033[1;31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

step 1 "Installerer git og GitHub CLI"
pkg update -y
pkg install -y git gh unzip

gh auth status >/dev/null 2>&1 || fail "Kjør: gh auth login"

step 2 "Starter GitHub-bygg"
gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$BRANCH"
sleep 3
RUN_ID="$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$BRANCH" --event workflow_dispatch --limit 1 --json databaseId --jq '.[0].databaseId')"
[[ -n "$RUN_ID" && "$RUN_ID" != "null" ]] || fail "Fant ikke workflow-run"
printf 'Run ID: %s\n' "$RUN_ID"

step 3 "Følger byggeloggen"
gh run watch "$RUN_ID" --repo "$REPO" --exit-status

step 4 "Laster ned APK-artifact"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
gh run download "$RUN_ID" --repo "$REPO" --name MonicaKey-debug-apk --dir "$TMP"
APK="$(find "$TMP" -type f -name '*.apk' -print -quit)"
[[ -s "$APK" ]] || fail "APK mangler i artifact"
mkdir -p "$(dirname "$OUT")"
cp -f "$APK" "$OUT"
sha256sum "$OUT"

step 5 "Installerer lokalt"
if command -v su >/dev/null 2>&1; then
  su -c "pm install -r '$OUT'" || termux-open "$OUT"
else
  termux-open "$OUT"
fi

printf '\nFerdig: %s\nLogg: %s\n' "$OUT" "$LOG"
