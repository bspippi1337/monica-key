#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REPO="${REPO:-bspippi1337/monica-key}"
WORKFLOW="${WORKFLOW:-showcase.yml}"
OUT="$HOME/storage/downloads"
[[ -d "$OUT" ]] || OUT="$HOME"

command -v gh >/dev/null || {
  echo "gh mangler"
  exit 1
}

gh auth status >/dev/null 2>&1 || {
  echo "Kjør først: gh auth login"
  exit 1
}

get_latest_run() {
  gh run list \
    --repo "$REPO" \
    --workflow "$WORKFLOW" \
    --branch main \
    --limit 1 \
    --json databaseId,status,conclusion \
    --jq '.[0] // {}'
}

run_json="$(get_latest_run)"
run_id="$(printf '%s' "$run_json" | jq -r '.databaseId // empty')"
run_status="$(printf '%s' "$run_json" | jq -r '.status // empty')"
run_conclusion="$(printf '%s' "$run_json" | jq -r '.conclusion // empty')"

if [[ -z "$run_id" || ( "$run_status" == "completed" && "$run_conclusion" != "success" ) ]]; then
  echo "Starter nytt showcase-bygg"
  gh workflow run "$WORKFLOW" --repo "$REPO" --ref main

  run_id=""
  for _ in $(seq 1 60); do
    run_json="$(get_latest_run)"
    run_id="$(printf '%s' "$run_json" | jq -r '.databaseId // empty')"
    [[ -n "$run_id" ]] && break
    sleep 2
  done
fi

[[ -n "$run_id" ]] || {
  echo "Fant ikke showcase-build"
  exit 1
}

echo "Følger showcase-build $run_id"
gh run watch "$run_id" --repo "$REPO" --exit-status

rm -f "$OUT/MonicaKey-Showcase.apk" "$OUT/SHA256SUMS.txt"

echo "Laster ned faktisk APK"
gh release download showcase \
  --repo "$REPO" \
  --pattern 'MonicaKey-Showcase.apk' \
  --pattern 'SHA256SUMS.txt' \
  --dir "$OUT" \
  --clobber

test -s "$OUT/MonicaKey-Showcase.apk"
sha256sum "$OUT/MonicaKey-Showcase.apk"
printf '\nAPK: %s\n' "$OUT/MonicaKey-Showcase.apk"

if command -v termux-open >/dev/null 2>&1; then
  termux-open "$OUT/MonicaKey-Showcase.apk"
fi
