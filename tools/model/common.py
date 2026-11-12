"""Shared definitions of the embedding-model tooling (docs/specs/embeddings.md §1 and §2).

Every input is pinned: repository revision and SHA-256 of each downloaded file. Every output goes to
tools/model/out/<id>/ (ignored by git); expected digests of the outputs live in
engine/embedding/model.lock.json.
"""

from __future__ import annotations

import hashlib
import json
import platform
import shutil
import struct
from dataclasses import dataclass
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parents[1]
DATA = TOOLS / "data"
OUT = TOOLS / "out"
CACHE = OUT / ".cache"
TESTDATA = REPO / "testdata"
LOCK = REPO / "engine" / "embedding" / "model.lock.json"
ASSETS = REPO / "engine" / "embedding" / "src" / "main" / "assets" / "model"

MODEL_FILE = "model.onnx"
FP32_FILE = "model_fp32.onnx"
VOCAB_FILE = "vocab.bin"
MANIFEST_FILE = "MODEL_MANIFEST.json"
SET_FILES = (MODEL_FILE, VOCAB_FILE, MANIFEST_FILE)
LOCKED_FILES = (MODEL_FILE, VOCAB_FILE)

DIMENSIONS = 384
MAX_PASSAGE_TOKENS = 256
MAX_QUERY_TOKENS = 64
PREFIXES = {"query": "query: ", "passage": "passage: "}
SPECIAL_TOKENS = ("<s>", "<pad>", "</s>", "<unk>")
VOCAB_MAGIC = b"SMV1"


@dataclass(frozen=True)
class SourceFile:
    """A file of the model repository, at the pinned revision."""

    path: str
    sha256: str


@dataclass(frozen=True)
class ModelSource:
    id: str
    model_id: int
    repo: str
    revision: str
    weights: SourceFile
    weights_format: str  # "safetensors": exported to ONNX here; "onnx": quantized as is
    tokenizer: SourceFile
    config: SourceFile
    vocab_size: int

    @property
    def out_dir(self) -> Path:
        return OUT / self.id


MODELS: dict[str, ModelSource] = {
    "fr": ModelSource(
        id="fr",
        model_id=1,
        repo="antoinelouis/french-me5-small",
        revision="1605fe3b64cb22dde93f30e8fbfb1889742d2753",
        weights=SourceFile("model.safetensors", "de4508e0e6fa9b71605ce5e00af423d741a8f28a40239d31a63ffbbd80bf900d"),
        weights_format="safetensors",
        tokenizer=SourceFile("tokenizer.json", "ddd4c161657f579d21beabbde3e1d89022f7c3795e40750a4b2de87ac7330030"),
        config=SourceFile("config.json", "fcd0dd36fcce391163ddca395e4c7c1b5930ec26c8539aad0c1a1fcd1b4b359a"),
        vocab_size=37_200,
    ),
    "multi": ModelSource(
        id="multi",
        model_id=2,
        repo="intfloat/multilingual-e5-small",
        revision="614241f622f53c4eeff9890bdc4f31cfecc418b3",
        weights=SourceFile("onnx/model.onnx", "ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665"),
        weights_format="onnx",
        tokenizer=SourceFile("tokenizer.json", "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"),
        config=SourceFile("config.json", "69137736cab8b8903a07fe8afaafdda25aac55415a12a55d1bffa9f581abf959"),
        vocab_size=250_002,
    ),
}


def model(model_id: str) -> ModelSource:
    try:
        return MODELS[model_id]
    except KeyError:
        raise SystemExit(f"unknown model '{model_id}': expected one of {', '.join(MODELS)}") from None


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(source: ModelSource, file: SourceFile) -> Path:
    """Downloads one file of the pinned revision (cached) and checks its SHA-256."""
    from huggingface_hub import hf_hub_download

    path = Path(
        hf_hub_download(
            repo_id=source.repo,
            filename=file.path,
            revision=source.revision,
            cache_dir=CACHE,
        )
    )
    actual = sha256(path)
    if actual != file.sha256:
        raise SystemExit(f"{source.repo}@{source.revision}/{file.path}: SHA-256 {actual}, expected {file.sha256}")
    return path


def read_lock() -> dict:
    return json.loads(LOCK.read_text(encoding="utf-8")) if LOCK.exists() else {}


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def file_entry(path: Path) -> dict:
    return {"sha256": sha256(path), "size": path.stat().st_size}


def tool_versions() -> dict:
    from importlib.metadata import version

    names = ("torch", "transformers", "onnx", "onnxruntime", "tokenizers", "numpy")
    versions = {"python": platform.python_version()}
    for name in names:
        try:
            versions[name] = version(name)
        except Exception:  # noqa: BLE001 - a tool absent from this environment is recorded as such
            versions[name] = None
    return versions


def check_against_lock(source: ModelSource, directory: Path) -> list[str]:
    """Differences between the files of a set and model.lock.json; empty when they conform."""
    entry = read_lock().get(source.id)
    if entry is None:
        return [f"{LOCK.name} has no entry for '{source.id}'"]
    problems = []
    for name in LOCKED_FILES:
        path = directory / name
        expected = entry["files"][name]
        if not path.exists():
            problems.append(f"{name}: missing")
        elif (actual := file_entry(path)) != expected:
            problems.append(f"{name}: {actual}, expected {expected}")
    return problems


def record_testdata(path: Path) -> None:
    """Records the digest of a reference file of testdata/ in model.lock.json (its generator only)."""
    lock = read_lock()
    entries = lock.setdefault("testdata", {})
    entries[path.name] = file_entry(path)
    lock["testdata"] = dict(sorted(entries.items()))
    write_json(LOCK, dict(sorted(lock.items())))


def install(source: ModelSource) -> Path:
    """Copies the set into the assets of :engine:embedding (ignored by git)."""
    target = ASSETS / source.id
    target.mkdir(parents=True, exist_ok=True)
    for name in SET_FILES:
        shutil.copyfile(source.out_dir / name, target / name)
    return target


# --- vocab.bin (docs/specs/embeddings.md §3.2) --------------------------------------------------


def encode_vocab(pieces: list[tuple[str, float]]) -> bytes:
    """`SMV1`, int32 count, then per token: uint16 byte length, UTF-8 bytes, float32 score."""
    chunks = [VOCAB_MAGIC, struct.pack("<i", len(pieces))]
    for piece, score in pieces:
        data = piece.encode("utf-8")
        if len(data) > 0xFFFF:
            raise ValueError(f"token too long: {len(data)} bytes")
        chunks.append(struct.pack("<H", len(data)))
        chunks.append(data)
        chunks.append(struct.pack("<f", score))
    return b"".join(chunks)


def decode_vocab(blob: bytes) -> list[tuple[str, float]]:
    if blob[:4] != VOCAB_MAGIC:
        raise ValueError("not a vocab.bin file (bad magic)")
    (count,) = struct.unpack_from("<i", blob, 4)
    offset = 8
    pieces = []
    for _ in range(count):
        (length,) = struct.unpack_from("<H", blob, offset)
        offset += 2
        piece = blob[offset : offset + length].decode("utf-8")
        offset += length
        (score,) = struct.unpack_from("<f", blob, offset)
        offset += 4
        pieces.append((piece, score))
    if offset != len(blob):
        raise ValueError(f"{len(blob) - offset} trailing bytes")
    return pieces


def unigram_pieces(tokenizer_json: dict) -> list[tuple[str, float]]:
    """The Unigram vocabulary of a tokenizer.json, in id order, with the checks of the spec."""
    model_section = tokenizer_json["model"]
    if model_section.get("type") != "Unigram":
        raise ValueError(f"expected a Unigram tokenizer, got {model_section.get('type')}")
    pieces = [(piece, float(score)) for piece, score in model_section["vocab"]]
    if tuple(piece for piece, _ in pieces[:4]) != SPECIAL_TOKENS:
        raise ValueError(f"unexpected special tokens: {[piece for piece, _ in pieces[:4]]}")
    if model_section.get("unk_id") != 3:
        raise ValueError(f"unexpected unk_id: {model_section.get('unk_id')}")
    return pieces
