"""Compares regenerated embedding references with the committed ones (workflow `model`).

    python tools/model/compare_embedding_golden.py [--min-cosine 0.9999]

Token ids and texts must be identical; vectors may differ in their last decimals from one processor to
another (int8 kernels of ONNX Runtime), so they are compared by cosine.
"""

from __future__ import annotations

import argparse
import json
import subprocess

import numpy as np

import common


def committed(path: str) -> dict:
    return json.loads(subprocess.run(["git", "show", f"HEAD:{path}"], cwd=common.REPO, check=True, capture_output=True, text=True).stdout)


def compare(model_id: str, min_cosine: float) -> list[str]:
    name = f"testdata/embedding_golden_{model_id}.json"
    old, new = committed(name), json.loads((common.REPO / name).read_text(encoding="utf-8"))
    problems = []
    if old["modelSha256"] != new["modelSha256"]:
        problems.append(f"{model_id}: model digest changed")
    if len(old["items"]) != len(new["items"]):
        return [*problems, f"{model_id}: {len(new['items'])} texts, expected {len(old['items'])}"]
    worst = 1.0
    for before, after in zip(old["items"], new["items"]):
        if (before["kind"], before["text"], before["inputIds"]) != (after["kind"], after["text"], after["inputIds"]):
            problems.append(f"{model_id}: text or token ids changed for {before['text']!r}")
        worst = min(worst, float(np.dot(before["vector"], after["vector"])))
    print(f"{model_id}: lowest cosine with the committed vectors {worst:.6f}")
    if worst < min_cosine:
        problems.append(f"{model_id}: cosine {worst:.6f} < {min_cosine}")
    return problems


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--min-cosine", type=float, default=0.9999)
    args = parser.parse_args()
    problems = [p for model_id in common.MODELS for p in compare(model_id, args.min_cosine)]
    if problems:
        raise SystemExit("\n".join(problems))


if __name__ == "__main__":
    main()
