"""Quality checks of an artefact set (docs/specs/embeddings.md §2).

    python tools/model/verify.py --model fr|multi [--lock]

- vocab.bin decodes, and matches the tokenizer of the pinned revision;
- MODEL_MANIFEST.json describes the files of the set;
- mean cosine between the original and the quantized model >= 0.98 on 200 sentences;
- Recall@5 by meaning on testdata/eval: the quantized model loses at most 0.03 (eval_set.py);
- with --lock: model.onnx and vocab.bin match engine/embedding/model.lock.json.
"""

from __future__ import annotations

import argparse
import json
import sys

import common
import eval_set
import numpy as np
import runtime

COSINE_SENTENCES = 200
MIN_MEAN_COSINE = 0.98


def verify(source: common.ModelSource) -> float:
    out = source.out_dir
    for name in (*common.SET_FILES, common.FP32_FILE):
        if not (out / name).exists():
            raise SystemExit(f"{source.id}: {name} missing in {out}: run build_model.py --model {source.id}")

    pieces = common.decode_vocab((out / common.VOCAB_FILE).read_bytes())
    expected = common.unigram_pieces(json.loads(common.fetch(source, source.tokenizer).read_text(encoding="utf-8")))
    if len(pieces) != len(expected) or any(p != e[0] or abs(s - e[1]) > 1e-5 for (p, s), e in zip(pieces, expected)):
        raise SystemExit(f"{source.id}: vocab.bin differs from tokenizer.json")

    manifest = json.loads((out / common.MANIFEST_FILE).read_text(encoding="utf-8"))
    for name in common.LOCKED_FILES:
        if manifest["files"][name] != common.file_entry(out / name):
            raise SystemExit(f"{source.id}: MODEL_MANIFEST.json does not describe {name}")
    if manifest["modelId"] != source.model_id or manifest["source"]["revision"] != source.revision:
        raise SystemExit(f"{source.id}: MODEL_MANIFEST.json names another model")

    tok = runtime.tokenizer(source, common.MAX_PASSAGE_TOKENS)
    original, quantized = runtime.session(out / common.FP32_FILE), runtime.session(out / common.MODEL_FILE)
    cosines = []
    for sentence in runtime.read_sentences()[:COSINE_SENTENCES]:
        ids = tok.encode(runtime.prefixed("passage", sentence)).ids
        cosines.append(float(np.dot(runtime.embed_ids(original, ids), runtime.embed_ids(quantized, ids))))
    mean = float(np.mean(cosines))
    print(f"{source.id}: mean cosine original/quantized {mean:.4f} (min {min(cosines):.4f}) on {len(cosines)} sentences")
    if mean < MIN_MEAN_COSINE:
        raise SystemExit(f"{source.id}: mean cosine {mean:.4f} < {MIN_MEAN_COSINE}")
    eval_set.check(source)
    return mean


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, choices=sorted(common.MODELS))
    parser.add_argument("--lock", action="store_true", help="also compare with model.lock.json")
    args = parser.parse_args()
    source = common.model(args.model)
    verify(source)
    if args.lock and (problems := common.check_against_lock(source, source.out_dir)):
        print(f"{source.id}: does not match model.lock.json:", *problems, sep="\n  ")
        sys.exit(1)


if __name__ == "__main__":
    main()
