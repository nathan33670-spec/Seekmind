#!/usr/bin/env bash
# Tests of scripts/check-forbidden-apis.sh and scripts/check-elf-alignment.sh (ticket M0-04).
# Each guard must accept a compliant input and reject a non-compliant one.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
failures=0

expect() { # expect <description> <expected exit code> <command…>
  local description="$1" expected="$2"; shift 2
  local output; output="$("$@" 2>&1)"; local status=$?
  if [ "$status" -eq "$expected" ]; then
    echo "ok   - $description"
  else
    echo "FAIL - $description (exit $status, expected $expected)"; echo "$output" | sed 's/^/       /'
    failures=$((failures + 1))
  fi
}

# --- check-forbidden-apis.sh -------------------------------------------------------------------
tree() { # tree <name>: a minimal repository with the real rules
  mkdir -p "$work/$1/gradle" && cp "$repo/gradle/forbidden-apis.txt" "$work/$1/gradle/" && echo "$work/$1"
}
put() { mkdir -p "$(dirname "$1")" && cat > "$1"; }

clean="$(tree clean)"
put "$clean/engine/capture/src/main/kotlin/A.kt" <<'K'
package a
// performGlobalAction is mentioned in a comment only
fun read(node: Any) = node.toString()
K
put "$clean/engine/ads/src/main/kotlin/Ads.kt" <<'K'
import com.google.android.gms.ads.AdRequest
val request = AdRequest.Builder().build()
K
put "$clean/core/common/src/main/kotlin/SmLog.kt" <<'K'
import android.util.Log
fun w(tag: String) = Log.w(tag, "count=1")
K
put "$clean/engine/search/src/test/kotlin/T.kt" <<'K'
import java.net.URL // tests are not scanned
K
expect "forbidden APIs: compliant tree passes" 0 "$repo/scripts/check-forbidden-apis.sh" "$clean"

check_bad() { # check_bad <description> <relative path> <source>
  local t; t="$(tree "bad$RANDOM")"; printf '%s\n' "$3" | put "$t/$2"
  expect "forbidden APIs: rejects $1" 1 "$repo/scripts/check-forbidden-apis.sh" "$t"
}
check_bad "a network API outside engine/ads" "engine/search/src/main/kotlin/N.kt" "val u = java.net.URL(\"https://x\")"
check_bad "the ads SDK outside engine/ads" "feature/paywall/src/main/kotlin/P.kt" "import com.google.android.gms.ads.MobileAds"
check_bad "ad targeting inside engine/ads" "engine/ads/src/main/kotlin/T.kt" "builder.addKeyword(query)"
check_bad "performGlobalAction" "engine/capture/src/main/kotlin/S.kt" "service.performGlobalAction(1)"
check_bad "a gesture" "engine/capture/src/main/kotlin/G.kt" "val g = GestureDescription.Builder()"
check_bad "Bitmap.compress" "engine/ocr/src/main/kotlin/C.kt" "bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)"
check_bad "a file written by the OCR module" "engine/ocr/src/main/kotlin/F.kt" "val out = FileOutputStream(file)"
check_bad "any compress call in the OCR module" "engine/ocr/src/main/kotlin/E.kt" "bitmap.compress(format, 90, out)"
check_bad "an ImageWriter in the capture module" "engine/capture/src/main/kotlin/I.kt" "val writer = ImageWriter.newInstance(surface, 1)"
check_bad "the cache directory in the OCR module" "engine/ocr/src/main/kotlin/D.kt" "val dir = context.cacheDir"
check_bad "a file stream in the capture module" "engine/capture/src/main/kotlin/O.kt" "File(dir, name).outputStream().use { }"
check_bad "MediaStore in the OCR module" "engine/ocr/src/main/kotlin/M.kt" "val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI"
check_bad "android.util.Log outside core/common" "engine/index/src/main/kotlin/L.kt" "Log.d(\"tag\", text)"
check_bad "a WebView" "feature/search/src/main/kotlin/W.kt" "val view = WebView(context)"
check_bad "the database key turned into text" "core/database/src/main/kotlin/fr/corsicajack/seekmind/core/database/key/K.kt" "val hex = key.joinToString(\"\")"
check_bad "the database key logged" "core/database/src/main/kotlin/fr/corsicajack/seekmind/core/database/key/L.kt" "SmLog.i(TAG, \"key\")"

# --- check-elf-alignment.sh --------------------------------------------------------------------
if command -v gcc >/dev/null && command -v objdump >/dev/null; then
  printf 'int seekmind_probe(void) { return 1; }\n' > "$work/probe.c"
  apk() { # apk <name> <max page size> [<abi directory>]
    mkdir -p "$work/$1/lib/${3:-x86_64}"
    gcc -shared -fPIC -O2 -Wl,-z,max-page-size="$2" -o "$work/$1/lib/${3:-x86_64}/libprobe.so" "$work/probe.c"
    (cd "$work/$1" && zip -q -r "$work/$1.apk" lib)
    echo "$work/$1.apk"
  }
  good="$(apk aligned16k 16384)"; bad="$(apk aligned4k 4096)"
  expect "ELF alignment: 16 KB aligned library passes" 0 env SKIP_ZIPALIGN=1 "$repo/scripts/check-elf-alignment.sh" "$good"
  expect "ELF alignment: 4 KB aligned library is rejected" 1 env SKIP_ZIPALIGN=1 "$repo/scripts/check-elf-alignment.sh" "$bad"
  # The host compiler builds a 64-bit library; only the directory decides which ABI the script sees.
  bad32="$(apk aligned4k32 4096 armeabi-v7a)"
  expect "ELF alignment: 4 KB aligned 32-bit library is only reported" 0 env SKIP_ZIPALIGN=1 "$repo/scripts/check-elf-alignment.sh" "$bad32"
else
  echo "skip - ELF alignment tests (gcc or objdump missing)"
fi

[ "$failures" -eq 0 ] && echo "All guard script tests passed." || { echo "$failures guard script test(s) failed."; exit 1; }
