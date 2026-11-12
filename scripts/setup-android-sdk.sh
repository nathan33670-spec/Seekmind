#!/usr/bin/env bash
# Installe les composants du SDK Android nécessaires pour compiler SeekMind
# (compilation, tests JVM, lint — pas d'émulateur : les tests sur appareil tournent en CI).
#
# Idempotent et non interactif. Utilisable en local, en CI et comme étape du
# script d'initialisation d'un environnement cloud d'agent IA.
#
#   scripts/setup-android-sdk.sh                 # installe dans $ANDROID_HOME ou /opt/android-sdk
#   ANDROID_HOME=$HOME/android-sdk scripts/setup-android-sdk.sh
#
# Hôtes réseau requis : dl.google.com
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
# Archive « cmdline-tools;latest » (cf. https://developer.android.com/studio#command-line-tools-only)
CLT_ZIP="${CLT_ZIP:-commandlinetools-linux-16111833_latest.zip}"

# Versions lues dans le catalogue Gradle (source unique, docs/03-architecture.md §8).
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
catalog="$repo_root/gradle/libs.versions.toml"
catalog_version() { sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$catalog" | head -1; }
COMPILE_SDK="$(catalog_version compileSdk)"
BUILD_TOOLS="$(catalog_version buildTools)"
[ -n "$COMPILE_SDK" ] && [ -n "$BUILD_TOOLS" ] || { echo "Versions introuvables dans $catalog" >&2; exit 1; }
PACKAGES=("platform-tools" "platforms;android-$COMPILE_SDK.0" "build-tools;$BUILD_TOOLS")

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo "==> Installation des command-line tools dans $ANDROID_HOME"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/clt.zip" "https://dl.google.com/android/repository/$CLT_ZIP"
  unzip -q "$tmp/clt.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi

echo "==> Acceptation des licences"
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true

echo "==> Installation : ${PACKAGES[*]}"
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "${PACKAGES[@]}"

# Gradle lit sdk.dir dans local.properties (fichier ignoré par git) quand
# ANDROID_HOME n'est pas exporté dans le shell qui lance le build.
if [ ! -f "$repo_root/local.properties" ]; then
  echo "sdk.dir=$ANDROID_HOME" > "$repo_root/local.properties"
  echo "==> local.properties créé ($repo_root/local.properties)"
fi

echo "==> SDK Android prêt : ANDROID_HOME=$ANDROID_HOME"
