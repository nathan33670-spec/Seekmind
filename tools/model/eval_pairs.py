"""First look at the quality of the models (prototype S4, ticket M0-09; risk R-06).

    python tools/model/eval_pairs.py

Twenty query/passage pairs written for the occasion (tools/model/data/quality_pairs.jsonl: ten in
French, ten in English, the same ten topics). For each model and each language, every query ranks the
ten passages of its language, and the whole set of twenty; reports top-1 accuracy, mean reciprocal rank
and the mean margin between the right passage and the best wrong one. Uses the quantized sets of
tools/model/out/ (run build_model.py first).
"""

from __future__ import annotations

import json

import numpy as np

import common
import runtime


def vectors(source: common.ModelSource, kind: str, texts: list[str]) -> np.ndarray:
    onnx_session = runtime.session(source.out_dir / common.MODEL_FILE)
    tok = runtime.tokenizer(source, runtime.max_tokens(kind))
    return np.stack([runtime.embed_ids(onnx_session, tok.encode(runtime.prefixed(kind, text)).ids) for text in texts])


def scores(queries: np.ndarray, passages: np.ndarray) -> dict:
    similarity = queries @ passages.T
    ranks = [int((row > row[i]).sum()) + 1 for i, row in enumerate(similarity)]
    margins = [row[i] - np.max(np.delete(row, i)) for i, row in enumerate(similarity)]
    return {
        "top1": sum(r == 1 for r in ranks) / len(ranks),
        "mrr": float(np.mean([1 / r for r in ranks])),
        "margin": float(np.mean(margins)),
    }


def main() -> None:
    pairs = [json.loads(line) for line in (common.DATA / "quality_pairs.jsonl").read_text(encoding="utf-8").splitlines()]
    print("| Modèle | Jeu | Top-1 | MRR | Marge moyenne |")
    print("|---|---|---|---|---|")
    for source in common.MODELS.values():
        q = vectors(source, "query", [p["query"] for p in pairs])
        d = vectors(source, "passage", [p["passage"] for p in pairs])
        for label, index in (
            ("français (10)", [i for i, p in enumerate(pairs) if p["lang"] == "fr"]),
            ("anglais (10)", [i for i, p in enumerate(pairs) if p["lang"] == "en"]),
            ("les 20 mélangés", list(range(len(pairs)))),
        ):
            s = scores(q[index], d[index])
            print(f"| `{source.id}` | {label} | {s['top1']:.0%} | {s['mrr']:.3f} | {s['margin']:.3f} |")
        cross = scores(q[[i for i, p in enumerate(pairs) if p["lang"] == "fr"]], d[[i for i, p in enumerate(pairs) if p["lang"] == "en"]])
        print(f"| `{source.id}` | requête FR → passage EN | {cross['top1']:.0%} | {cross['mrr']:.3f} | {cross['margin']:.3f} |")


if __name__ == "__main__":
    main()
