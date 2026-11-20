#!/usr/bin/env bash
# Fails if a system log holds captured content (INV-07, ticket M10-05): any of the distinctive words of
# the fixture app (scripts/logcat-markers.txt), whatever its case.
#   scripts/check-logcat-content.sh <logcat file>
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
markers="${LOGCAT_MARKERS:-$here/logcat-markers.txt}"
log="${1:?usage: check-logcat-content.sh <logcat file>}"
[ -s "$log" ] || { echo "check-logcat-content: $log is missing or empty" >&2; exit 2; }

patterns="$(mktemp)"; trap 'rm -f "$patterns"' EXIT
grep -v -e '^#' -e '^[[:space:]]*$' "$markers" > "$patterns"

# The instrumentation reports a failed assertion with its message, which may name a marker the test
# was waiting for: those lines come from the test runner, not from SeekMind, and are left out.
if hits="$(grep -i -F -f "$patterns" "$log" | grep -v -E ' (TestRunner|MonitoringInstr|TestExecutor)\b')"; then
  echo "check-logcat-content: captured content in the system log ($(echo "$hits" | wc -l) lines):" >&2
  echo "$hits" | head -20 >&2
  exit 1
fi
echo "check-logcat-content: OK ($(wc -l < "$log") lines, $(wc -l < "$patterns") markers)."
