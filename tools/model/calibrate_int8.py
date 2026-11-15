"""Calibration of the int8 scale of the vector codec (docs/specs/embeddings.md §6, ticket M4-05).

    python tools/model/calibrate_int8.py --model fr|multi

Embeds the reference sentences as passages with the quantized model, then reports, for a range of
scales S, the share of components clipped by round(v × S) outside [-127, 127] and the mean cosine
between the vectors and their int8 codes. The scale kept is the largest whose clipping stays under
MAX_CLIPPED: the largest scale uses the int8 range best.
"""

from __future__ import annotations

import argparse

import numpy as np

import common
import runtime

SCALES = (300, 350, 400, 450, 500, 550, 600, 700)
MAX_CLIPPED = 0.001
SENTENCES = 1_000


def vectors(source: common.ModelSource) -> np.ndarray:
    session = runtime.session(source.out_dir / common.MODEL_FILE)
    tok = runtime.tokenizer(source, runtime.max_tokens("passage"))
    sentences = runtime.read_sentences()[:SENTENCES]
    return np.stack([runtime.embed_ids(session, tok.encode(runtime.prefixed("passage", s)).ids) for s in sentences])


def clipped_share(v: np.ndarray, scale: float) -> float:
    return float(np.mean(np.abs(np.rint(v * scale)) > 127))


def int8_cosine(v: np.ndarray, scale: float) -> float:
    codes = np.clip(np.rint(v * scale), -127, 127)
    return float(np.mean(np.sum(v * codes, axis=1) / (np.linalg.norm(v, axis=1) * np.linalg.norm(codes, axis=1))))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, choices=sorted(common.MODELS))
    args = parser.parse_args()
    v = vectors(common.model(args.model))
    print(f"{args.model}: {len(v)} sentences, max |component| {np.max(np.abs(v)):.4f}")
    kept = None
    for scale in SCALES:
        share = clipped_share(v, scale)
        print(f"  S={scale}: clipped {share * 100:.4f} %, mean cosine to int8 {int8_cosine(v, scale):.6f}")
        if share < MAX_CLIPPED:
            kept = scale
    print(f"{args.model}: largest scale under {MAX_CLIPPED * 100} % clipped: {kept}")


if __name__ == "__main__":
    main()
