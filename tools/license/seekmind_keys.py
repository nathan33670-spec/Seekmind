#!/usr/bin/env python3
"""ES256 key pairs for the activation codes (docs/specs/monetisation.md §5.3, prerequisites §3).

    seekmind_keys.py keygen --purpose license --kid l1 --out DIR

writes DIR/<kid>.pem (private key, PKCS#8 PEM: keep it offline, never in the repository) and
DIR/<kid>.spki (public key, base64 X.509, to paste in keys-release.json).
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

sys.path.insert(0, str(Path(__file__).resolve().parent))
from jws import spki_b64  # noqa: E402

KID = re.compile(r"^[A-Za-z0-9-]{1,16}$")
PURPOSES = {"license"}


REPOSITORY = Path(__file__).resolve().parent.parent.parent


def _inside_repository(path: Path) -> bool:
    resolved = path.resolve()
    return resolved == REPOSITORY or REPOSITORY in resolved.parents


def keygen(purpose: str, kid: str, out: Path) -> tuple[Path, Path]:
    if _inside_repository(out):
        raise ValueError(f"{out} is inside the repository: write the private key elsewhere")
    if purpose not in PURPOSES:
        raise ValueError(f"unknown purpose: {purpose}")
    if not KID.match(kid):
        raise ValueError(f"invalid kid: {kid}")
    out.mkdir(parents=True, exist_ok=True)
    private_path, public_path = out / f"{kid}.pem", out / f"{kid}.spki"
    if private_path.exists() or public_path.exists():
        raise FileExistsError(f"{private_path} or {public_path} already exists")
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    # The private key is readable by its owner only.
    fd = os.open(private_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    public_path.write_text(spki_b64(key.public_key()) + "\n", encoding="ascii")
    return private_path, public_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    gen = sub.add_parser("keygen", help="create an ES256 key pair")
    gen.add_argument("--purpose", required=True, choices=sorted(PURPOSES))
    gen.add_argument("--kid", required=True)
    # Required, and never inside the repository: a production key must not lie in a working copy.
    gen.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    private_path, public_path = keygen(args.purpose, args.kid, args.out)
    print(f"private key: {private_path} (keep it offline)")
    print(f"public key:  {public_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
