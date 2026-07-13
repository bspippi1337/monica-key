#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REPO="${REPO:-bspippi1337/monica-key}"
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

rm -f "$OUT/MonicaKey-Showcase.apk" "$OUT/SHA256SUMS.txt"

gh release download showcase \
  --repo "$REPO" \
  --pattern 'MonicaKey-Showcase.apk' \
  --pattern 'SHA256SUMS.txt' \
  --dir "$OUT" \
  --clobber

sha256sum "$OUT/MonicaKey-Showcase.apk"
printf '\nAPK: %s\n' "$OUT/MonicaKey-Showcase.apk"

command -v termux-open >/dev/null 2>&1 && termux-open "$OUT/MonicaKey-Showcase.apk" || true
