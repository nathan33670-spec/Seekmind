"""The .smpack archive (docs/specs/embeddings.md §5.2)."""

import dataclasses
import zipfile

import common
import pack_model
import pytest


@pytest.fixture
def fake_set(tmp_path, monkeypatch):
    out = tmp_path / "out"
    source = dataclasses.replace(common.MODELS["fr"], id="fake")
    directory = out / "fake"
    directory.mkdir(parents=True)
    for name, content in ((common.MODEL_FILE, b"onnx" * 100), (common.VOCAB_FILE, b"SMV1\x00\x00\x00\x00"), (common.MANIFEST_FILE, b"{}")):
        (directory / name).write_bytes(content)
    monkeypatch.setattr(common, "OUT", out)
    lock = {"fake": {"files": {name: common.file_entry(directory / name) for name in common.LOCKED_FILES}}}
    monkeypatch.setattr(common, "read_lock", lambda: lock)
    return source, directory


def test_archive_holds_the_three_files_stored_in_a_fixed_order(fake_set):
    source, directory = fake_set
    target = pack_model.pack(source)
    with zipfile.ZipFile(target) as archive:
        assert archive.namelist() == [common.MANIFEST_FILE, common.VOCAB_FILE, common.MODEL_FILE]
        for info in archive.infolist():
            assert info.compress_type == zipfile.ZIP_STORED
            assert info.date_time == pack_model.FIXED_DATE
            assert archive.read(info) == (directory / info.filename).read_bytes()


def test_archive_is_reproducible(fake_set, tmp_path):
    source, _ = fake_set
    first = pack_model.pack(source, tmp_path / "a.smpack").read_bytes()
    second = pack_model.pack(source, tmp_path / "b.smpack").read_bytes()
    assert first == second


def test_a_set_that_does_not_match_the_lock_is_not_packed(fake_set):
    source, directory = fake_set
    (directory / common.MODEL_FILE).write_bytes(b"tampered")
    with pytest.raises(SystemExit, match="does not match"):
        pack_model.pack(source)
