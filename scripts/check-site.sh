#!/usr/bin/env bash
# Checks the static site (ticket M11-02): every page declares the strict content security policy and
# loads nothing from outside, and only the relay page of the activation links has a script.
# With --release, a page that still holds a placeholder ("todo" marker) is an error too (ticket M11-06).
#   scripts/check-site.sh [--release] <site directory>
set -euo pipefail

release=0
if [ "${1:-}" = "--release" ]; then release=1; shift; fi
site="${1:?usage: check-site.sh [--release] <site directory>}"
failures=0
fail() { echo "check-site: $1" >&2; failures=$((failures + 1)); }

shopt -s nullglob
pages=("$site"/*.html)
[ "${#pages[@]}" -gt 0 ] || { echo "check-site: no page in $site" >&2; exit 2; }
for page in "${pages[@]}"; do
  name="$(basename "$page")"
  grep -q "http-equiv=\"Content-Security-Policy\" content=\"default-src 'none';" "$page" || fail "$name: no strict content security policy"
  # Nothing is loaded from elsewhere: no src, no stylesheet, no frame.
  if grep -n -i -E '<(img|iframe|audio|video|source|embed|object)\b|<link[^>]*rel="?stylesheet|src="(https?:)?//' "$page"; then
    fail "$name: loads a resource"
  fi
  if [ "$name" != "a.html" ] && grep -q -i '<script' "$page"; then fail "$name: has a script"; fi
  if [ "$release" -eq 1 ] && grep -q 'class="todo"' "$page"; then fail "$name: placeholder left to fill in"; fi
done
[ "$failures" -eq 0 ] || exit 1
echo "check-site: OK (${#pages[@]} pages)."
