"""Converts the tokenizer.json of a model into vocab.bin (docs/specs/embeddings.md §3.2).

python tools/model/export_vocab.py --model fr|multi
"""

from __future__ import annotations

import argparse
import json

import common


def export_vocab(source: common.ModelSource) -> int:
    tokenizer = json.loads(common.fetch(source, source.tokenizer).read_text(encoding="utf-8"))
    pieces = common.unigram_pieces(tokenizer)
    if len(pieces) != source.vocab_size:
        raise SystemExit(f"{source.id}: {len(pieces)} tokens, expected {source.vocab_size}")
    target = source.out_dir / common.VOCAB_FILE
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(common.encode_vocab(pieces))
    return len(pieces)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, choices=sorted(common.MODELS))
    args = parser.parse_args()
    source = common.model(args.model)
    count = export_vocab(source)
    path = source.out_dir / common.VOCAB_FILE
    print(f"{source.id}: {count} tokens, {path.stat().st_size} bytes -> {path.relative_to(common.REPO)}")


if __name__ == "__main__":
    main()
