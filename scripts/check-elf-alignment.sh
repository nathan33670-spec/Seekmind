#!/usr/bin/env bash
# Verifies that native libraries are compatible with 16 KB memory pages (ticket M0-04, ENF-COMP-01):
#  - every LOAD segment of every lib/*/*.so is aligned on at least 2**14 bytes;
#  - the APK itself is zip-aligned for 16 KB pages (zipalign -P 16), when zipalign is available.
#
#   scripts/check-elf-alignment.sh                  # release APKs of :app
#   scripts/check-elf-alignment.sh <apk> [<apk>…]
# SKIP_ZIPALIGN=1 skips the zip check (script tests use hand-made archives).
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ "$#" -eq 0 ]; then
  mapfile -t apks < <(find "$repo/app/build/outputs/apk" -path '*/release/*.apk' 2>/dev/null | sort)
  [ "${#apks[@]}" -gt 0 ] || { echo "No release APK found: run assembleAdsRelease assembleOfflineRelease first." >&2; exit 2; }
else
  apks=("$@")
fi

zipalign=""
if [ "${SKIP_ZIPALIGN:-0}" != "1" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ] && [ -f "$repo/local.properties" ]; then
    sdk="$(sed -n 's/^sdk.dir=//p' "$repo/local.properties")"
  fi
  zipalign="$(ls -d "$sdk"/build-tools/*/zipalign 2>/dev/null | sort -V | tail -1 || true)"
fi

failures=0
for apk in "${apks[@]}"; do
  work="$(mktemp -d)"
  unzip -q -o "$apk" 'lib/*' -d "$work" 2>/dev/null || true
  count=0
  while IFS= read -r so; do
    count=$((count + 1))
    while read -r align; do
      exponent="$(sed -n 's/^2\*\*\([0-9][0-9]*\)$/\1/p' <<< "$align")"
      if [ -z "$exponent" ] || [ "$exponent" -lt 14 ]; then
        echo "$(basename "$apk"): ${so#"$work"/}: LOAD segment aligned on $align (< 2**14)"
        failures=$((failures + 1))
        break
      fi
    done < <(objdump -p "$so" | awk '$1 == "LOAD" { print $NF }')
  done < <(find "$work" -name '*.so' | sort)
  rm -rf "$work"

  if [ -n "$zipalign" ]; then
    if ! "$zipalign" -c -P 16 4 "$apk" >/dev/null; then
      echo "$(basename "$apk"): not zip-aligned for 16 KB pages"
      failures=$((failures + 1))
    fi
  fi
  echo "$(basename "$apk"): $count native librar$([ "$count" -eq 1 ] && echo y || echo ies) checked"
done

if [ "$failures" -gt 0 ]; then
  echo "check-elf-alignment: $failures problem(s)." >&2
  exit 1
fi
echo "check-elf-alignment: OK"
