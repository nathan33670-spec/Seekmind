"""Writes testdata/embedding_golden_<id>.json: 50 texts, their token ids and their reference vectors.

    python tools/model/gen_embedding_golden.py --model fr|multi

Vectors come from the quantized model of tools/model/out/<id>/ (run build_model.py first), executed by
ONNX Runtime in Python: prefix ("query: " or "passage: "), truncation (64 or 256 tokens), masked mean
pooling, L2 normalisation. The instrumented tests of the engine require a cosine >= 0.99 with each
vector (docs/specs/embeddings.md §10); prototype M0-09 feeds the stored token ids to the model directly.
"""

from __future__ import annotations

import argparse
import json
import sys

import common
import runtime

DECIMALS = 6


def exact_int8_arithmetic() -> bool:
    """False on an x86 processor without VNNI, where the u8s8 kernels of ONNX Runtime saturate.

    The references must reflect the exact int8 arithmetic of the phones (ARM dot-product instructions),
    which x86 offers only with AVX-VNNI or AVX512-VNNI.
    """
    import platform
    from pathlib import Path

    if platform.machine().lower() not in ("x86_64", "amd64"):
        return True
    cpuinfo = Path("/proc/cpuinfo")
    if not cpuinfo.exists():
        return False
    flags = cpuinfo.read_text(encoding="utf-8", errors="replace")
    return "avx512_vnni" in flags or "avx_vnni" in flags


def golden(source: common.ModelSource) -> dict:
    if problems := common.check_against_lock(source, source.out_dir):
        raise SystemExit(f"{source.id}: the set does not match model.lock.json: {problems}")
    model_path = source.out_dir / common.MODEL_FILE
    onnx_session = runtime.session(model_path)
    tokenizers = {kind: runtime.tokenizer(source, runtime.max_tokens(kind)) for kind in common.PREFIXES}
    items = []
    for line in (common.DATA / "embedding_texts.jsonl").read_text(encoding="utf-8").splitlines():
        entry = json.loads(line)
        ids = tokenizers[entry["kind"]].encode(runtime.prefixed(entry["kind"], entry["text"])).ids
        vector = runtime.embed_ids(onnx_session, ids)
        items.append({**entry, "inputIds": ids, "vector": [round(float(v), DECIMALS) for v in vector]})
    return {
        "model": source.id,
        "modelId": source.model_id,
        "modelSha256": common.sha256(model_path),
        "dimensions": common.DIMENSIONS,
        "prefixes": common.PREFIXES,
        "maxTokens": {"query": common.MAX_QUERY_TOKENS, "passage": common.MAX_PASSAGE_TOKENS},
        "items": items,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, choices=sorted(common.MODELS))
    parser.add_argument("--allow-inexact-int8", action="store_true", help="run on an x86 processor without VNNI (not for committed files)")
    args = parser.parse_args()
    if not args.allow_inexact_int8 and not exact_int8_arithmetic():
        raise SystemExit(
            "this processor has no exact int8 arithmetic (x86 without VNNI): the vectors would differ from those "
            "of a phone; generate the references on an ARM or VNNI machine, or pass --allow-inexact-int8 to compare only"
        )
    source = common.model(args.model)
    target = common.TESTDATA / f"embedding_golden_{source.id}.json"
    value = golden(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    common.record_testdata(target)
    print(f"{source.id}: {len(value['items'])} texts -> {target.relative_to(common.REPO)}", file=sys.stdout)


if __name__ == "__main__":
    main()
