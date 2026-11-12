"""engine/embedding/model.lock.json and the committed reference files (docs/specs/embeddings.md §2)."""

import json

import common
import pytest


@pytest.fixture(scope="module")
def lock():
    return common.read_lock()


def test_lock_has_both_models_with_their_pinned_source(lock):
    for model_id, source in common.MODELS.items():
        entry = lock[model_id]
        assert entry["modelId"] == source.model_id
        assert entry["repo"] == source.repo
        assert entry["revision"] == source.revision
        assert set(entry["files"]) == set(common.LOCKED_FILES)
        for file in entry["files"].values():
            assert len(file["sha256"]) == 64 and file["size"] > 0


def test_model_sizes_match_the_spec_targets(lock):
    # About 36 MB and 118 MB (docs/specs/embeddings.md §1).
    assert 30e6 < lock["fr"]["files"]["model.onnx"]["size"] < 42e6
    assert 105e6 < lock["multi"]["files"]["model.onnx"]["size"] < 130e6


def test_committed_reference_files_match_their_digests(lock):
    expected = {f"tokenizer_golden_{m}.jsonl" for m in common.MODELS} | {f"embedding_golden_{m}.json" for m in common.MODELS}
    assert set(lock["testdata"]) == expected
    for name, entry in lock["testdata"].items():
        assert common.file_entry(common.TESTDATA / name) == entry, f"{name} was edited outside its generator"


def test_tokenizer_references_cover_every_source_sentence():
    sentences = [line for line in (common.DATA / "tokenizer_sentences.txt").read_text(encoding="utf-8").split("\n") if line]
    for model_id in common.MODELS:
        rows = [
            json.loads(line) for line in (common.TESTDATA / f"tokenizer_golden_{model_id}.jsonl").read_text(encoding="utf-8").splitlines()
        ]
        assert [row["text"] for row in rows] == sentences
        for row in rows:
            assert row["ids"][0] == 0 and row["ids"][-1] == 2  # <s> … </s>
            assert all(0 <= i < common.MODELS[model_id].vocab_size for i in row["ids"])


def test_embedding_references_are_normalised_and_complete(lock):
    for model_id, source in common.MODELS.items():
        golden = json.loads((common.TESTDATA / f"embedding_golden_{model_id}.json").read_text(encoding="utf-8"))
        assert golden["modelId"] == source.model_id
        assert golden["modelSha256"] == lock[model_id]["files"]["model.onnx"]["sha256"]
        assert len(golden["items"]) == 50
        for item in golden["items"]:
            assert item["kind"] in common.PREFIXES
            assert len(item["vector"]) == common.DIMENSIONS
            assert abs(sum(v * v for v in item["vector"]) - 1.0) < 1e-4
            assert item["inputIds"][0] == 0 and item["inputIds"][-1] == 2
            limit = common.MAX_QUERY_TOKENS if item["kind"] == "query" else common.MAX_PASSAGE_TOKENS
            assert len(item["inputIds"]) <= limit


def test_check_against_lock_reports_missing_and_altered_files(tmp_path, monkeypatch):
    model = tmp_path / "model.onnx"
    vocab = tmp_path / "vocab.bin"
    model.write_bytes(b"model")
    vocab.write_bytes(b"vocab")
    lock = {"fr": {"files": {"model.onnx": common.file_entry(model), "vocab.bin": common.file_entry(vocab)}}}
    monkeypatch.setattr(common, "read_lock", lambda: lock)
    source = common.MODELS["fr"]
    assert common.check_against_lock(source, tmp_path) == []
    vocab.write_bytes(b"other")
    assert [p.split(":")[0] for p in common.check_against_lock(source, tmp_path)] == ["vocab.bin"]
    model.unlink()
    assert "model.onnx: missing" in common.check_against_lock(source, tmp_path)
