#!/usr/bin/env python3
"""Checks every package of engine/privacy/src/main/assets/sensitive_apps.json on the Play Store
(docs/specs/confidentialite.md §3.2, ticket M2-05).

A package is verified when its public Play Store page answers 200 for at least one country of the
launch market. Three packages are not on the Play Store and are listed in UNLISTED with their source:
the F-Droid one is checked on F-Droid, the two system ones are accepted as such. The report lists each package with the title of its page; the script fails when one is
missing. Run from the repository root, with network access:

    python tools/privacy/verify_sensitive_apps.py [--report build/sensitive_apps_report.tsv]
"""
import argparse
import concurrent.futures
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ASSET = pathlib.Path("engine/privacy/src/main/assets/sensitive_apps.json")
COUNTRIES = ["FR", "BE", "CH", "LU", "ES", "DE", "IT"]
HEADERS = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"}

# Packages that the Play Store does not distribute, and where they come from.
UNLISTED = {
    "com.android.settings": "system",  # the settings of Android itself
    "com.samsung.android.samsungpass": "system",  # Samsung Pass, preinstalled on Samsung phones
    "com.kunzisoft.keepass.libre": "f-droid",  # KeePassDX, libre edition
}


def page(package, country):
    url = "https://play.google.com/store/apps/details?" + urllib.parse.urlencode(
        {"id": package, "hl": "en", "gl": country})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=30) as r:
                html = r.read().decode("utf-8", "replace")
                title = re.search(r"<title[^>]*>([^<]*?) - Apps on Google Play", html)
                return 200, title.group(1) if title else ""
        except urllib.error.HTTPError as e:
            return e.code, ""
        except OSError:
            time.sleep(2 * (attempt + 1))
    return 0, ""


def fdroid(package):
    url = f"https://f-droid.org/en/packages/{package}/"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=30) as r:
            return r.status == 200
    except (urllib.error.HTTPError, OSError):
        return False


def verify(package):
    source = UNLISTED.get(package)
    if source == "system":
        return package, "system", "(not on the Play Store: system application)"
    if source == "f-droid":
        return (package, "f-droid", "(F-Droid)") if fdroid(package) else (package, "missing", "")
    for country in COUNTRIES:
        status, title = page(package, country)
        if status == 200:
            return package, country, title
        if status == 0:
            return package, "error", ""
    return package, "missing", ""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=pathlib.Path)
    args = parser.parse_args()
    data = json.loads(ASSET.read_text(encoding="utf-8"))
    packages = [(kind, p) for kind in ("strict", "soft") for p in data[kind]["packages"]]
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        results = dict((r[0], r) for r in pool.map(verify, [p for _, p in packages]))
    lines = [f"{kind}\t{p}\t{results[p][1]}\t{results[p][2]}" for kind, p in packages]
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text("kind\tpackage\tcountry\ttitle\n" + "\n".join(lines) + "\n", encoding="utf-8")
    bad = [line for line in lines if line.split("\t")[2] in ("missing", "error")]
    print(f"{len(packages)} packages, {len(packages) - len(bad)} verified, {len(bad)} not found")
    for line in bad:
        print("NOT FOUND:", line)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
