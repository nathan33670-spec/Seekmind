"""Tests of the licence issuing tool (ticket M8-05)."""

import base64
import csv
import json
import stat
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HERE))

import gen_test_vectors  # noqa: E402
from cryptography.hazmat.primitives import serialization  # noqa: E402
from jws import b64url_decode, load_private_pkcs8_b64, spki_b64  # noqa: E402
from seekmind_license import issue, main, verify_code  # noqa: E402

NOW = 1_794_441_600


@pytest.fixture
def test_key(tmp_path) -> tuple[Path, str]:
    """The TEST key l-test as a PEM file, and its public part."""
    key = load_private_pkcs8_b64(gen_test_vectors.TEST_KEY.read_text())
    pem = tmp_path / "l-test.pem"
    pem.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    return pem, spki_b64(key.public_key())


def test_issue_writes_identifiers_codes_and_links_readable_by_the_owner_only(tmp_path, test_key):
    pem, spki = test_key
    out = tmp_path / "out" / "codes.csv"
    rows = issue(pem, "l-test", "lifetime", 5, "example.org", out, now=NOW)
    assert stat.S_IMODE(out.stat().st_mode) == 0o600
    with out.open(newline="") as f:
        written = list(csv.DictReader(f))
    assert written == rows
    assert len({row["license_id"] for row in rows}) == 5
    for row in rows:
        assert row["license_id"].startswith("SM-")
        assert row["link"] == f"https://example.org/a#{row['code']}"
        claims = verify_code(spki, row["code"])
        assert claims == {"kid": "l-test", "typ": "license", "lid": row["license_id"], "plan": "lifetime", "iat": NOW}


def test_an_expiry_is_written_only_when_asked(tmp_path, test_key):
    pem, spki = test_key
    rows = issue(pem, "l-test", "lifetime", 1, "example.org", tmp_path / "c.csv", valid_days=30, now=NOW)
    assert verify_code(spki, rows[0]["code"])["exp"] == NOW + 30 * 86_400


def test_issue_refuses_the_repository_and_never_overwrites(tmp_path, test_key):
    pem, _ = test_key
    with pytest.raises(ValueError):
        issue(pem, "l-test", "lifetime", 1, "example.org", HERE / "codes.csv")
    out = tmp_path / "codes.csv"
    issue(pem, "l-test", "lifetime", 1, "example.org", out)
    with pytest.raises(FileExistsError):
        issue(pem, "l-test", "lifetime", 1, "example.org", out)


@pytest.mark.parametrize(
    "kid,plan,count,site,prefix",
    [
        ("../x", "lifetime", 1, "example.org", "SM"),
        ("l-test", "Life time", 1, "example.org", "SM"),
        ("l-test", "lifetime", 0, "example.org", "SM"),
        ("l-test", "lifetime", 1, "https://example.org", "SM"),
        ("l-test", "lifetime", 1, "example.org/a", "SM"),
        ("l-test", "lifetime", 1, "example.org", "sm-"),
    ],
)
def test_issue_refuses_invalid_arguments(tmp_path, test_key, kid, plan, count, site, prefix):
    pem, _ = test_key
    with pytest.raises(ValueError):
        issue(pem, kid, plan, count, site, tmp_path / "c.csv", prefix=prefix)


def test_verify_refuses_an_altered_code_and_another_key(tmp_path, test_key):
    pem, spki = test_key
    code = issue(pem, "l-test", "lifetime", 1, "example.org", tmp_path / "c.csv")[0]["code"]
    head, body, signature = code.split(".")
    raw = bytearray(b64url_decode(signature))
    raw[0] ^= 1
    altered = f"{head}.{body}.{base64.urlsafe_b64encode(bytes(raw)).rstrip(b'=').decode()}"
    with pytest.raises(ValueError):
        verify_code(spki, altered)
    other = tmp_path / "other"
    __import__("seekmind_keys").keygen("license", "l9", other)
    with pytest.raises(ValueError):
        verify_code((other / "l9.spki").read_text(), code)


def test_verify_command_exit_codes(tmp_path, test_key, capsys):
    pem, spki = test_key
    code = issue(pem, "l-test", "lifetime", 1, "example.org", tmp_path / "c.csv")[0]["code"]
    pub = tmp_path / "l-test.spki"
    pub.write_text(spki)
    assert main(["verify", "--pub", str(pub), code]) == 0
    assert json.loads(capsys.readouterr().out)["typ"] == "license"
    assert main(["verify", "--pub", str(pub), "not.a.code"]) == 1


def test_the_tool_issued_vector_is_committed():
    vectors = json.loads((HERE.parent.parent / "testdata/tokens/vectors.json").read_text())["vectors"]
    assert any(v["name"] == "issued_by_tool" and v["expected"] == "VALID" for v in vectors)
