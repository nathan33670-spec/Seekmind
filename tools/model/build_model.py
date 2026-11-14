"""Builds the artefact set of an embedding model (docs/specs/embeddings.md §2).

    python tools/model/build_model.py --model fr [--install] [--update-lock]
    python tools/model/build_model.py --model multi

Steps: download the pinned weights; export them to ONNX (model `fr`, distributed as safetensors only);
quantize them (dynamic int8); write vocab.bin and MODEL_MANIFEST.json; run the quality checks of
verify.py; compare with engine/embedding/model.lock.json. `--install` copies the set into the assets of
:engine:embedding. `--update-lock` records the digests of this build in model.lock.json (to be done only
when a pinned input or tool version changes, in its own pull request).
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

import common
import export_vocab
import verify

OPSET = 17


def export_fr(source: common.ModelSource, target: Path) -> None:
    """Exports the safetensors weights to an ONNX graph: input_ids, attention_mask -> last_hidden_state."""
    import torch
    from transformers import AutoModel

    weights = common.fetch(source, source.weights)
    common.fetch(source, source.config)
    model = AutoModel.from_pretrained(weights.parent, local_files_only=True).eval()

    class Encoder(torch.nn.Module):
        def __init__(self, inner: torch.nn.Module) -> None:
            super().__init__()
            self.inner = inner

        def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
            return self.inner(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state

    ids = torch.tensor([[0, 1_000, 2_000, 3_000, 2]], dtype=torch.int64)
    mask = torch.ones_like(ids)
    with torch.no_grad():
        torch.onnx.export(
            Encoder(model),
            (ids, mask),
            str(target),
            input_names=["input_ids", "attention_mask"],
            output_names=["last_hidden_state"],
            dynamic_axes={
                "input_ids": {0: "batch", 1: "sequence"},
                "attention_mask": {0: "batch", 1: "sequence"},
                "last_hidden_state": {0: "batch", 1: "sequence"},
            },
            opset_version=OPSET,
            do_constant_folding=True,
            dynamo=False,
        )


def quantize(source_model: Path, target: Path) -> None:
    from onnxruntime.quantization import QuantType, quantize_dynamic

    # Per-channel scales (ticket M5-07, decision of the project owner on 14 November 2026): per-tensor
    # scales cost 0.10 of Recall@5 by meaning to fr on testdata/eval. Unsigned weights (u8u8) keep the
    # x86 emulators of the CI, without VNNI, from saturating where u8s8 would.
    quantize_dynamic(
        model_input=str(source_model),
        model_output=str(target),
        weight_type=QuantType.QUInt8,
        per_channel=True,
        reduce_range=False,
    )


def build(source: common.ModelSource) -> None:
    out = source.out_dir
    out.mkdir(parents=True, exist_ok=True)
    fp32 = out / common.FP32_FILE
    if source.weights_format == "safetensors":
        print(f"{source.id}: exporting {source.weights.path} to ONNX (opset {OPSET})")
        export_fr(source, fp32)
    else:
        print(f"{source.id}: using {source.weights.path}")
        shutil.copyfile(common.fetch(source, source.weights), fp32)
    print(f"{source.id}: quantizing (dynamic, per channel, uint8 weights)")
    quantize(fp32, out / common.MODEL_FILE)
    export_vocab.export_vocab(source)
    write_manifest(source)


def write_manifest(source: common.ModelSource) -> None:
    out = source.out_dir
    common.write_json(
        out / common.MANIFEST_FILE,
        {
            "id": source.id,
            "modelId": source.model_id,
            "source": {
                "repo": source.repo,
                "revision": source.revision,
                "weights": source.weights.path,
                "weightsSha256": source.weights.sha256,
                "tokenizerSha256": source.tokenizer.sha256,
            },
            "dimensions": common.DIMENSIONS,
            "vocabSize": source.vocab_size,
            "quantization": {"method": "dynamic", "weightType": "QInt8", "perChannel": False, "reduceRange": False},
            "files": {name: common.file_entry(out / name) for name in common.LOCKED_FILES},
            "tools": common.tool_versions(),
        },
    )


def update_lock(source: common.ModelSource) -> None:
    lock = common.read_lock()
    lock[source.id] = {
        "modelId": source.model_id,
        "repo": source.repo,
        "revision": source.revision,
        "files": {name: common.file_entry(source.out_dir / name) for name in common.LOCKED_FILES},
    }
    common.write_json(common.LOCK, dict(sorted(lock.items())))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, choices=sorted(common.MODELS))
    parser.add_argument("--install", action="store_true", help="copy the set into the assets of :engine:embedding")
    parser.add_argument("--update-lock", action="store_true", help="record this build's digests in model.lock.json")
    args = parser.parse_args()
    source = common.model(args.model)

    build(source)
    verify.verify(source)
    if args.update_lock:
        update_lock(source)
        print(f"{source.id}: {common.LOCK.relative_to(common.REPO)} updated")
    problems = common.check_against_lock(source, source.out_dir)
    if problems:
        print(f"{source.id}: does not match {common.LOCK.relative_to(common.REPO)}:", *problems, sep="\n  ")
        sys.exit(1)
    for name in common.LOCKED_FILES:
        size = (source.out_dir / name).stat().st_size
        print(f"{source.id}: {name} {size / 1_048_576:.1f} MiB")
    if args.install:
        print(f"{source.id}: installed in {common.install(source).relative_to(common.REPO)}")


if __name__ == "__main__":
    main()
