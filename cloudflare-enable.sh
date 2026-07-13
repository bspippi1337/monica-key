#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REPO="${REPO:-bspippi1337/monica-key}"
WORKFLOW="${WORKFLOW:-build.yml}"

command -v gh >/dev/null || {
  echo "gh mangler"
  exit 1
}

gh auth status >/dev/null 2>&1 || {
  echo "Kjør først: gh auth login"
  exit 1
}

echo
echo "Lim inn Cloudflare Account ID når gh spør:"
gh secret set CLOUDFLARE_ACCOUNT_ID --repo "$REPO"

echo
echo "Lim inn Cloudflare API-token når gh spør:"
gh secret set CLOUDFLARE_API_TOKEN --repo "$REPO"

echo
echo "Starter automatisk deploy og APK-bygg."
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

[[ -n "$RUN_ID" ]] || {
  echo "Fant ikke workflow-run"
  exit 1
}

gh run watch "$RUN_ID" --repo "$REPO" --exit-status

echo
echo "Relay og APK-er er publisert automatisk."
echo "Kjør ./termux-build.sh for å hente APK-ene."
