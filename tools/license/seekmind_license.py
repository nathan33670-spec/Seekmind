#!/usr/bin/env python3
"""Activation codes of SeekMind (docs/specs/monetisation.md §5.3 to §5.5).

    seekmind_license.py issue --key DIR/l1.pem --kid l1 --plan lifetime --count 50 \\
        --site example.org --out DIR/codes.csv
    seekmind_license.py verify --pub DIR/l1.spki <code>

`issue` writes a CSV of licence identifier, code and activation link (https://<site>/a#<code>). The
codes activate SeekMind: the CSV is kept outside the repository, like the private key, and never
overwritten. `verify` checks the signature and prints the claims of a code.
"""

from __future__ import annotations

import argparse
import base64
import csv
import json
import os
import re
import secrets
import sys
import time
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

sys.path.insert(0, str(Path(__file__).resolve().parent))
from jws import b64url_decode, sign, verify  # noqa: E402

REPOSITORY = Path(__file__).resolve().parent.parent.parent
KID = re.compile(r"^[A-Za-z0-9-]{1,16}$")
PLAN = re.compile(r"^[a-z][a-z0-9_-]{0,31}$")
PREFIX = re.compile(r"^[A-Z0-9]{1,8}$")
HOST = re.compile(r"^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
MAX_COUNT = 10_000
# Same limit as the application's verifier (TokenVerifier.MAX_LENGTH).
MAX_LENGTH = 2_048


def _inside_repository(path: Path) -> bool:
    resolved = path.resolve()
    return resolved == REPOSITORY or REPOSITORY in resolved.parents


def new_license_id(prefix: str) -> str:
    """`<prefix>-<10 hexadecimal digits>`: traceable by the publisher, carries nothing about the user."""
    return f"{prefix}-{secrets.token_hex(5).upper()}"


def issue_code(key: ec.EllipticCurvePrivateKey, kid: str, plan: str, lid: str, iat: int, exp: int | None) -> str:
    payload = {"typ": "license", "lid": lid, "plan": plan, "iat": iat}
    if exp is not None:
        payload["exp"] = exp
    code = sign(key, {"alg": "ES256", "kid": kid}, payload)
    if len(code) > MAX_LENGTH:
        raise ValueError("code longer than the application accepts")
    return code


def activation_link(site: str, code: str) -> str:
    # The fragment is never sent to the server (§5.5).
    return f"https://{site}/a#{code}"


def issue(
    key_path: Path,
    kid: str,
    plan: str,
    count: int,
    site: str,
    out: Path,
    prefix: str = "SM",
    valid_days: int | None = None,
    now: int | None = None,
) -> list[dict]:
    if _inside_repository(out):
        raise ValueError(f"{out} is inside the repository: codes activate SeekMind, keep them elsewhere")
    if out.exists():
        raise FileExistsError(f"{out} already exists")
    if not KID.match(kid):
        raise ValueError(f"invalid kid: {kid}")
    if not PLAN.match(plan):
        raise ValueError(f"invalid plan: {plan}")
    if not PREFIX.match(prefix):
        raise ValueError(f"invalid prefix: {prefix}")
    if not HOST.match(site):
        raise ValueError(f"invalid site host: {site}")
    if not 1 <= count <= MAX_COUNT:
        raise ValueError(f"count must be between 1 and {MAX_COUNT}")
    if valid_days is not None and valid_days < 1:
        raise ValueError("valid days must be at least 1")
    key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise ValueError("not a P-256 private key")
    iat = int(time.time()) if now is None else now
    exp = iat + valid_days * 86_400 if valid_days is not None else None
    rows, seen = [], set()
    while len(rows) < count:
        lid = new_license_id(prefix)
        if lid in seen:
            continue
        seen.add(lid)
        code = issue_code(key, kid, plan, lid, iat, exp)
        rows.append({"license_id": lid, "code": code, "link": activation_link(site, code)})
    out.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", newline="", encoding="ascii") as f:
        writer = csv.DictWriter(f, fieldnames=["license_id", "code", "link"])
        writer.writeheader()
        writer.writerows(rows)
    return rows


def verify_code(public_spki_b64: str, code: str) -> dict:
    """The header and claims of [code] when its signature is valid for the key; raises otherwise."""
    public = serialization.load_der_public_key(base64.b64decode(public_spki_b64.strip()))
    if not isinstance(public, ec.EllipticCurvePublicKey):
        raise ValueError("not an EC public key")
    code = code.strip()
    if len(code) > MAX_LENGTH or code.count(".") != 2:
        raise ValueError("not a code")
    head, body, _ = code.split(".")
    header = json.loads(b64url_decode(head))
    if header.get("alg") != "ES256":
        raise ValueError("algorithm is not ES256")
    if not verify(public, code):
        raise ValueError("invalid signature")
    claims = json.loads(b64url_decode(body))
    if claims.get("typ") != "license":
        raise ValueError("not a licence code")
    return {"kid": header.get("kid"), **claims}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    iss = sub.add_parser("issue", help="issue licence codes")
    iss.add_argument("--key", type=Path, required=True, help="private key, PEM (seekmind_keys.py keygen)")
    iss.add_argument("--kid", required=True)
    iss.add_argument("--plan", default="lifetime")
    iss.add_argument("--count", type=int, required=True)
    iss.add_argument("--site", required=True, help="host of the relay page a.html, e.g. example.org")
    iss.add_argument("--prefix", default="SM", help="prefix of the licence identifiers")
    iss.add_argument("--valid-days", type=int, default=None, help="expiry; none by default")
    iss.add_argument("--out", type=Path, required=True, help="CSV, outside the repository")
    ver = sub.add_parser("verify", help="verify a code")
    ver.add_argument("--pub", type=Path, required=True, help="public key, base64 SPKI (<kid>.spki)")
    ver.add_argument("code")
    args = parser.parse_args(argv)
    if args.command == "issue":
        rows = issue(args.key, args.kid, args.plan, args.count, args.site, args.out, args.prefix, args.valid_days)
        print(f"{len(rows)} codes written to {args.out} (keep it outside the repository)")
        return 0
    try:
        claims = verify_code(args.pub.read_text(encoding="ascii"), args.code)
    except (ValueError, json.JSONDecodeError) as error:
        print(f"refused: {error}", file=sys.stderr)
        return 1
    print(json.dumps(claims, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
