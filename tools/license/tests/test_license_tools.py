"""Tests of the licence key tools (ticket M8-02)."""

import json
import stat
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))

import gen_test_vectors  # noqa: E402
from cryptography.hazmat.primitives import serialization  # noqa: E402
from jws import load_private_pkcs8_b64, sign, verify  # noqa: E402
from seekmind_keys import keygen  # noqa: E402


def test_keygen_writes_a_private_pem_readable_by_its_owner_only_and_a_public_spki(tmp_path):
    private_path, public_path = keygen("license", "l9", tmp_path)
    assert stat.S_IMODE(private_path.stat().st_mode) == 0o600
    key = serialization.load_pem_private_key(private_path.read_bytes(), password=None)
    token = sign(key, {"alg": "ES256", "kid": "l9"}, {"typ": "license"})
    public = serialization.load_der_public_key(__import__("base64").b64decode(public_path.read_text()))
    assert verify(public, token)


def test_keygen_refuses_a_directory_of_the_repository():
    with pytest.raises(ValueError):
        keygen("license", "l9", HERE / "out")
    with pytest.raises(SystemExit):
        __import__("seekmind_keys").main(["keygen", "--purpose", "license", "--kid", "l9"])


def test_keygen_never_overwrites_a_key(tmp_path):
    keygen("license", "l9", tmp_path)
    with pytest.raises(FileExistsError):
        keygen("license", "l9", tmp_path)


@pytest.mark.parametrize("purpose,kid", [("other", "l9"), ("license", "../escape"), ("license", "")])
def test_keygen_refuses_an_unknown_purpose_or_an_invalid_kid(tmp_path, purpose, kid):
    with pytest.raises(ValueError):
        keygen(purpose, kid, tmp_path)


def test_the_signature_is_raw_r_and_s():
    key = load_private_pkcs8_b64(gen_test_vectors.TEST_KEY.read_text())
    token = sign(key, {"alg": "ES256", "kid": "l-test"}, {"typ": "license"})
    assert len(__import__("jws").b64url_decode(token.split(".")[2])) == 64
    assert verify(key.public_key(), token)


def test_the_committed_vectors_and_keyring_match_the_test_key():
    root = HERE.parent.parent / "testdata" / "tokens"
    assert json.loads((root / "keys-test.json").read_text()) == gen_test_vectors.keyring()
    vectors = json.loads((root / "vectors.json").read_text())["vectors"]
    names = [v["name"] for v in vectors]
    assert names == [v["name"] for v in gen_test_vectors.vectors()]
    key = load_private_pkcs8_b64(gen_test_vectors.TEST_KEY.read_text()).public_key()
    for vector in vectors:
        if vector["expected"] == "VALID":
            assert verify(key, vector["token"]), vector["name"]


def test_the_test_key_is_not_the_production_one():
    keys = json.loads((HERE.parent.parent / "engine/monetization/src/main/resources/license/keys-release.json").read_text())
    spkis = {k["spki"] for k in keys["keys"]}
    assert gen_test_vectors.keyring()["keys"][0]["spki"] not in spkis
