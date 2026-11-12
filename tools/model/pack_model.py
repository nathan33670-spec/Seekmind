"""Packs an artefact set into the `.smpack` archive read by FileModelPackInstaller.

    python tools/model/pack_model.py --model fr|multi

Writes tools/model/out/<id>.smpack: a ZIP archive, entries stored without compression (the model is
already dense), in a fixed order and with a fixed date so that the archive is reproducible:
MODEL_MANIFEST.json, vocab.bin, model.onnx. The set must match model.lock.json
(docs/specs/embeddings.md §5.2).
"""

from __future__ import annotations

import argparse
import zipfile
from pathlib import Path

import common

ENTRIES = (common.MANIFEST_FILE, common.VOCAB_FILE, common.MODEL_FILE)
FIXED_DATE = (1980, 1, 1, 0, 0, 0)


def pack(source: common.ModelSource, target: Path | None = None) -> Path:
    if problems := common.check_against_lock(source, source.out_dir):
        raise SystemExit(f"{source.id}: the set does not match model.lock.json: {problems}")
    target = target or common.OUT / f"{source.id}.smpack"
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED) as archive:
        for name in ENTRIES:
            info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o644 << 16
            with (source.out_dir / name).open("rb") as stream, archive.open(info, "w", force_zip64=True) as entry:
                for block in iter(lambda: stream.read(1 << 20), b""):
                    entry.write(block)
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, choices=sorted(common.MODELS))
    args = parser.parse_args()
    source = common.model(args.model)
    target = pack(source)
    print(f"{source.id}: {target.stat().st_size / 1_048_576:.1f} MiB -> {target.relative_to(common.REPO)}")


if __name__ == "__main__":
    main()
