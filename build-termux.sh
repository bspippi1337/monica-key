#!/data/data/com.termux/files/usr/bin/bash
set -Eeuo pipefail

REPO_URL="${REPO_URL:-https://github.com/bspippi1337/blckswan.git}"
BRANCH="${BRANCH:-agent/monica-key-live}"
WORK="${WORK:-$HOME/monica-key-src}"
GRADLE_VERSION="${GRADLE_VERSION:-8.11.1}"
GRADLE_HOME="$HOME/.local/gradle-$GRADLE_VERSION"
LOG="${LOG:-$HOME/monica-key-build.log}"

exec > >(tee -a "$LOG") 2>&1

step() { printf '\n\033[1;33m[%s] %s\033[0m\n' "$1" "$2"; }
fail() { printf '\n\033[1;31mFAILED: %s\033[0m\n' "$*" >&2; exit 1; }

step 1 "Installerer byggverktøy"
pkg update -y
pkg install -y git openjdk-17 wget unzip aapt2 apksigner

step 2 "Henter Monica Key"
if [[ -d "$WORK/.git" ]]; then
  git -C "$WORK" fetch origin "$BRANCH"
  git -C "$WORK" checkout -B "$BRANCH" "origin/$BRANCH"
else
  rm -rf "$WORK"
  git clone --depth=1 --branch "$BRANCH" "$REPO_URL" "$WORK"
fi

step 3 "Setter opp Gradle $GRADLE_VERSION"
if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  tmp="$(mktemp -d)"
  wget -q --show-progress "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -O "$tmp/gradle.zip"
  mkdir -p "$HOME/.local"
  unzip -q "$tmp/gradle.zip" -d "$HOME/.local"
  rm -rf "$tmp"
fi
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export PATH="$GRADLE_HOME/bin:$PATH"

step 4 "Bygger debug APK"
cd "$WORK/monica-key"
gradle --no-daemon --stacktrace :app:assembleDebug

APK="$WORK/monica-key/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || fail "APK ble ikke produsert"

step 5 "Kopierer ferdig APK"
OUT="$HOME/storage/downloads/MonicaKey-debug.apk"
mkdir -p "$(dirname "$OUT")"
cp -f "$APK" "$OUT"
sha256sum "$OUT"
printf '\nAPK: %s\nLogg: %s\n' "$OUT" "$LOG"

if command -v su >/dev/null 2>&1 && [[ "${INSTALL:-1}" == "1" ]]; then
  step 6 "Installerer APK lokalt med root"
  su -c "pm install -r '$OUT'" || {
    printf 'Direkte installasjon feilet. Åpner Androids pakkeinstallasjon i stedet.\n'
    termux-open "$OUT" 2>/dev/null || true
  }
else
  step 6 "Åpner APK i pakkeinstallasjonen"
  termux-open "$OUT" 2>/dev/null || true
fi
