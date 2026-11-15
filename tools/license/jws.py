"""Compact JWS ES256 tokens of SeekMind (docs/specs/monetisation.md §5.1).

`base64url(header) . base64url(payload) . base64url(signature)`, the signature in the raw `r || s` form
of 64 bytes. Shared by the key tool and the test-vector generator.
"""

from __future__ import annotations

import base64
import json

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature, encode_dss_signature

COORDINATE_BYTES = 32


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def encode_json(value: dict) -> str:
    return b64url(json.dumps(value, separators=(",", ":"), sort_keys=False).encode("utf-8"))


def sign(private_key: ec.EllipticCurvePrivateKey, header: dict, payload: dict) -> str:
    """Signs [header] and [payload]; returns the compact token."""
    signing_input = f"{encode_json(header)}.{encode_json(payload)}"
    der = private_key.sign(signing_input.encode("ascii"), ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    raw = r.to_bytes(COORDINATE_BYTES, "big") + s.to_bytes(COORDINATE_BYTES, "big")
    return f"{signing_input}.{b64url(raw)}"


def verify(public_key: ec.EllipticCurvePublicKey, token: str) -> bool:
    """True when the signature of [token] is valid for [public_key] (no other check)."""
    head, body, signature = token.split(".")
    raw = b64url_decode(signature)
    if len(raw) != 2 * COORDINATE_BYTES:
        return False
    der = encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
    try:
        public_key.verify(der, f"{head}.{body}".encode("ascii"), ec.ECDSA(hashes.SHA256()))
        return True
    except Exception:  # noqa: BLE001 - any failure is an invalid signature
        return False


def spki_b64(public_key: ec.EllipticCurvePublicKey) -> str:
    """The X.509 SubjectPublicKeyInfo of [public_key], base64 (the `spki` of the keyrings)."""
    der = public_key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode("ascii")


def load_private_pkcs8_b64(text: str) -> ec.EllipticCurvePrivateKey:
    key = serialization.load_der_private_key(base64.b64decode(text.strip()), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise ValueError("not a P-256 private key")
    return key
