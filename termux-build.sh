#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REPO="${REPO:-bspippi1337/monica-key}"
WORKFLOW="${WORKFLOW:-build.yml}"
DOWNLOADS="$HOME/storage/downloads"
[[ -d "$DOWNLOADS" ]] || DOWNLOADS="$HOME"

command -v gh >/dev/null || { echo "gh mangler"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Kjør: gh auth login"; exit 1; }

echo "[1/4] Starter GitHub-bygg"
gh workflow run "$WORKFLOW" --repo "$REPO" --ref main

RUN_ID=""
for _ in $(seq 1 60); do
  RUN_ID="$(gh run list \
    --repo "$REPO" \
    --workflow "$WORKFLOW" \
    --branch main \
    --event workflow_dispatch \
    --limit 1 \
    --json databaseId \
    --jq '.[0].databaseId // empty')"
  [[ -n "$RUN_ID" ]] && break
  sleep 2
done

[[ -n "$RUN_ID" ]] || { echo "Fant ikke workflow-run"; exit 1; }

echo "[2/4] Følger build $RUN_ID"
gh run watch "$RUN_ID" --repo "$REPO" --exit-status

echo "[3/4] Henter APK-ene fra release/latest"
rm -f \
  "$DOWNLOADS/PippiKey-debug.apk" \
  "$DOWNLOADS/MonicaKey-debug.apk" \
  "$DOWNLOADS/SHA256SUMS.txt"

gh release download latest \
  --repo "$REPO" \
  --pattern 'PippiKey-debug.apk' \
  --pattern 'MonicaKey-debug.apk' \
  --pattern 'SHA256SUMS.txt' \
  --dir "$DOWNLOADS"

echo "[4/4] Ferdig"
sha256sum \
  "$DOWNLOADS/PippiKey-debug.apk" \
  "$DOWNLOADS/MonicaKey-debug.apk"

printf '\nPippi: %s\nMonica: %s\n' \
  "$DOWNLOADS/PippiKey-debug.apk" \
  "$DOWNLOADS/MonicaKey-debug.apk"
