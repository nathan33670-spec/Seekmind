#!/usr/bin/env bash
# Fails when shipped source code uses a forbidden API (ticket M0-04, AGENTS.md §2).
# Rules: gradle/forbidden-apis.txt.
#
#   scripts/check-forbidden-apis.sh            # checks this repository
#   scripts/check-forbidden-apis.sh <root>     # checks another tree (used by the script tests)
set -euo pipefail

root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
rules="$root/gradle/forbidden-apis.txt"
[ -f "$rules" ] || { echo "Missing rules file: $rules" >&2; exit 2; }

mapfile -t files < <(
  cd "$root" && find app core engine feature -type f \( -name '*.kt' -o -name '*.java' \) \
    -not -path '*/build/*' -not -path '*/src/test/*' -not -path '*/src/androidTest/*' 2>/dev/null | sort
)

# in_scope <relative path> <scope>
in_scope() {
  local path="$1" scope="$2" kind="${2%%:*}" dirs dir match=1
  [ "$scope" = "all" ] && return 0
  IFS=',' read -r -a dirs <<< "${scope#*:}"
  for dir in "${dirs[@]}"; do
    [[ "$path" == "$dir/"* ]] && match=0
  done
  if [ "$kind" = "only" ]; then return $match; fi
  if [ "$kind" = "except" ]; then [ $match -ne 0 ]; return; fi
  echo "Unknown scope: $scope" >&2; exit 2
}

violations=0
while IFS=$'\t' read -r id scope regex message; do
  [[ -z "${id// }" || "$id" == \#* ]] && continue
  for file in "${files[@]}"; do
    in_scope "$file" "$scope" || continue
    while IFS= read -r hit; do
      [ -z "$hit" ] && continue
      line="${hit%%:*}"; code="${hit#*:}"; trimmed="${code#"${code%%[![:space:]]*}"}"
      case "$trimmed" in //*|/\**|\**) continue ;; esac
      echo "$file:$line: [$id] $message"
      echo "    $trimmed"
      violations=$((violations + 1))
    done < <(grep -nE -- "$regex" "$root/$file" || true)
  done
done < "$rules"

if [ "$violations" -gt 0 ]; then
  echo "check-forbidden-apis: $violations violation(s)." >&2
  exit 1
fi
echo "check-forbidden-apis: OK (${#files[@]} files)."
