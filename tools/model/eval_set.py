"""Non-regression of the quantized model on the search evaluation set (ticket M5-07, task 5).

    python tools/model/eval_set.py --model fr|multi

Ranks the 298 captures of testdata/eval/corpus.jsonl by cosine with each of the 80 queries of
testdata/eval/queries.jsonl, with the original (fp32) and the quantized model, and compares their
Recall@5. Meaning only, without the time and application filters of the real search: it measures what
quantization costs, not the search itself (that is SearchEvalDeviceTest, on a device). Called by
verify.py, hence by build_model.py, with strict=True: the build fails when quantization loses more than MAX_DROP
(docs/specs/embeddings.md §2). Without --strict, the command line only reports the loss.
"""

from __future__ import annotations

import argparse
import json

import numpy as np

import common
import runtime

EVAL = common.REPO / "testdata" / "eval"
MAX_DROP = 0.03


def read(name: str) -> list[dict]:
    lines = (EVAL / name).read_text(encoding="utf-8").splitlines()
    return [json.loads(line) for line in lines if line.strip()]


def recall5(source: common.ModelSource, model_file: str) -> float:
    corpus = read("corpus.jsonl")
    queries = [q for q in read("queries.jsonl") if "meta" not in q]
    session = runtime.session(source.out_dir / model_file)
    passage_tok = runtime.tokenizer(source, runtime.max_tokens("passage"))
    query_tok = runtime.tokenizer(source, runtime.max_tokens("query"))
    passages = np.stack([
        runtime.embed_ids(session, passage_tok.encode(runtime.prefixed("passage", item["text"])).ids)
        for item in corpus
    ])
    keys = [item["key"] for item in corpus]
    found = 0
    for query in queries:
        vector = runtime.embed_ids(session, query_tok.encode(runtime.prefixed("query", query["text"])).ids)
        top = np.argsort(-(passages @ vector))[:5]
        found += any(keys[i] in query["expected"] for i in top)
    return found / len(queries)


def check(source: common.ModelSource, strict: bool = False) -> tuple[float, float]:
    original = recall5(source, common.FP32_FILE)
    quantized = recall5(source, common.MODEL_FILE)
    print(f"{source.id}: Recall@5 by meaning on testdata/eval, original {original:.3f}, quantized {quantized:.3f}")
    if quantized < original - MAX_DROP:
        message = f"{source.id}: quantization loses {original - quantized:.3f} of Recall@5 (> {MAX_DROP})"
        if strict:
            raise SystemExit(message)
        print(f"WARNING {message}")
    return original, quantized


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, choices=sorted(common.MODELS))
    parser.add_argument("--strict", action="store_true", help=f"fail when quantization loses more than {MAX_DROP}")
    args = parser.parse_args()
    check(common.model(args.model), strict=args.strict)


if __name__ == "__main__":
    main()
