#!/usr/bin/env python3
"""Regenerates testdata/tokens/ (docs/specs/monetisation.md §5.3) with the TEST key `l-test`.

The test key protects nothing: its private part is committed (test_keys/), its public part is the
`l-test` entry of the debug keyring. A release build does not know it.

Each vector: a name, a token and the expected outcome of TokenVerifier (`VALID` or a refusal reason).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from jws import b64url, b64url_decode, encode_json, load_private_pkcs8_b64, sign, spki_b64  # noqa: E402

ROOT = HERE.parent.parent
OUT = ROOT / "testdata" / "tokens"
TEST_KID = "l-test"
TEST_KEY = HERE / "test_keys" / "l-test.TEST-KEY-ONLY.pkcs8.b64"
IAT = 1794441600  # 2026-11-13, fixed so that the vectors do not depend on the day of generation


def license_payload(**extra) -> dict:
    return {"typ": "license", "lid": "TEST-0001", "plan": "lifetime", "iat": IAT, **extra}


def vectors() -> list[dict]:
    key = load_private_pkcs8_b64(TEST_KEY.read_text(encoding="ascii"))
    header = {"alg": "ES256", "kid": TEST_KID}
    valid = sign(key, header, license_payload())
    head, body, signature = valid.split(".")

    def flip_last(segment: str) -> str:
        raw = bytearray(b64url_decode(segment))
        raw[-1] ^= 0x01
        return b64url(bytes(raw))

    out = [
        ("valid", valid, "VALID"),
        ("valid_with_future_exp", sign(key, header, license_payload(exp=IAT + 10 * 365 * 86400)), "VALID"),
        ("altered_signature", f"{head}.{body}.{flip_last(signature)}", "BAD_SIGNATURE"),
        ("altered_payload", f"{head}.{encode_json(license_payload(plan='other'))}.{signature}", "BAD_SIGNATURE"),
        ("alg_none", f"{encode_json({'alg': 'none', 'kid': TEST_KID})}.{body}.", "MALFORMED"),
        ("alg_none_signed", sign(key, {"alg": "none", "kid": TEST_KID}, license_payload()), "BAD_ALGORITHM"),
        ("alg_hs256", sign(key, {"alg": "HS256", "kid": TEST_KID}, license_payload()), "BAD_ALGORITHM"),
        ("alg_lowercase", sign(key, {"alg": "es256", "kid": TEST_KID}, license_payload()), "BAD_ALGORITHM"),
        ("unknown_kid", sign(key, {"alg": "ES256", "kid": "l-unknown"}, license_payload()), "UNKNOWN_KEY"),
        ("missing_kid", sign(key, {"alg": "ES256"}, license_payload()), "UNKNOWN_KEY"),
        ("unexpected_purpose", sign(key, header, {**license_payload(), "typ": "other"}), "WRONG_PURPOSE"),
        ("too_long", sign(key, header, license_payload(pad="x" * 2_100)), "TOO_LONG"),
        ("two_segments", f"{head}.{body}", "MALFORMED"),
        ("invalid_base64", f"{head}.{body}.{signature[:-2]}*!", "MALFORMED"),
        ("padding_not_base64url", f"{head}=.{body}.{signature}", "MALFORMED"),
        ("short_signature", f"{head}.{body}.{b64url(b'0' * 63)}", "BAD_SIGNATURE"),
        ("header_not_json", f"{b64url(b'not json')}.{body}.{signature}", "MALFORMED"),
        ("payload_not_json", sign_raw(key, header, b"not json"), "BAD_PAYLOAD"),
        ("payload_missing_lid", sign(key, header, {"typ": "license", "plan": "lifetime", "iat": IAT}), "BAD_PAYLOAD"),
        ("payload_iat_string", sign(key, header, {**license_payload(), "iat": "yesterday"}), "BAD_PAYLOAD"),
        ("payload_exp_string", sign(key, header, license_payload(exp="never")), "BAD_PAYLOAD"),
    ]
    return [{"name": name, "token": token, "expected": expected} for name, token, expected in out]


def sign_raw(key, header: dict, payload: bytes) -> str:
    """A token whose payload is [payload] as is (not JSON), correctly signed."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

    signing_input = f"{encode_json(header)}.{b64url(payload)}"
    r, s = decode_dss_signature(key.sign(signing_input.encode("ascii"), ec.ECDSA(hashes.SHA256())))
    return f"{signing_input}.{b64url(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


def keyring() -> dict:
    key = load_private_pkcs8_b64(TEST_KEY.read_text(encoding="ascii"))
    return {"keys": [{"kid": TEST_KID, "purpose": "license", "spki": spki_b64(key.public_key())}]}


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "vectors.json").write_text(json.dumps({"vectors": vectors()}, indent=2) + "\n", encoding="utf-8")
    (OUT / "keys-test.json").write_text(json.dumps(keyring(), indent=2) + "\n", encoding="utf-8")
    print(f"wrote {OUT}/vectors.json and keys-test.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
