#!/usr/bin/env bash
# Checks the prerequisites of a development machine or agent environment (ticket M0-05).
# Prints one line per prerequisite and, for each missing one, the command that fixes it.
#
#   scripts/doctor.sh             # what every build needs
#   scripts/doctor.sh --release   # also the embedding model, needed by release builds (from M0-10)
# Exit code: 0 when every required prerequisite is present, 1 otherwise.
set -uo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
catalog="$repo/gradle/libs.versions.toml"
release=0; [ "${1:-}" = "--release" ] && release=1
missing=0

ok()   { printf '  ok      %s\n' "$1"; }
fail() { printf '  MISSING %s\n          fix: %s\n' "$1" "$2"; missing=$((missing + 1)); }
info() { printf '  note    %s\n' "$1"; }
catalog_version() { sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$catalog" | head -1; }

echo "SeekMind — prerequisites"

# Java 17 or newer (docs/04-prerequis.md §4).
if command -v java >/dev/null 2>&1; then
  java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
  if [ "${java_major:-0}" -ge 17 ]; then ok "Java $java_major"; else fail "Java 17+ (found ${java_major:-unknown})" "install a JDK 17 or 21 and put it on PATH"; fi
else
  fail "Java 17+" "install a JDK 17 or 21 and put it on PATH"
fi

# Android SDK: location, platform and build tools from the catalog.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f "$repo/local.properties" ]; then sdk="$(sed -n 's/^sdk.dir=//p' "$repo/local.properties")"; fi
compile_sdk="$(catalog_version compileSdk)"; build_tools="$(catalog_version buildTools)"
if [ -z "$sdk" ] || [ ! -d "$sdk" ]; then
  fail "Android SDK (ANDROID_HOME or sdk.dir in local.properties)" "scripts/setup-android-sdk.sh"
else
  ok "Android SDK at $sdk"
  [ -f "$sdk/platforms/android-$compile_sdk.0/android.jar" ] && ok "platform android-$compile_sdk.0" \
    || fail "platform android-$compile_sdk.0" "ANDROID_HOME=$sdk scripts/setup-android-sdk.sh"
  [ -x "$sdk/build-tools/$build_tools/zipalign" ] && ok "build-tools $build_tools" \
    || fail "build-tools $build_tools" "ANDROID_HOME=$sdk scripts/setup-android-sdk.sh"
  [ -f "$repo/local.properties" ] || [ -n "${ANDROID_HOME:-}" ] \
    || fail "local.properties (sdk.dir)" "echo sdk.dir=$sdk > local.properties"
fi

# Python 3.13+ for tools/: the versions of tools/model/requirements.txt are pinned for it (docs/04-prerequis.md §4).
if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 13) else 1)'; then
  ok "Python $(python3 -c 'import platform; print(platform.python_version())')"
else
  fail "Python 3.13+" "install Python 3.13 or newer"
fi

# Binary tools used by the guard scripts.
for tool in objdump unzip; do
  command -v "$tool" >/dev/null 2>&1 && ok "$tool" || fail "$tool (scripts/check-elf-alignment.sh)" "install binutils / unzip"
done

# Embedding model, produced by tools/model (ticket M0-10); builds without -PskipModel need it.
model="$repo/engine/embedding/src/main/assets/model/fr"
if [ -f "$model/model.onnx" ] && [ -f "$model/vocab.bin" ]; then
  ok "embedding model (fr)"
elif [ "$release" -eq 1 ]; then
  fail "embedding model (fr)" "python tools/model/build_model.py --model fr --install"
else
  info "embedding model absent: pass -PskipModel to Gradle, or run scripts/doctor.sh --release for details"
fi

if [ "$missing" -gt 0 ]; then
  echo "$missing prerequisite(s) missing."
  exit 1
fi
echo "All required prerequisites are present."
