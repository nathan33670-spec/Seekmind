"""Writes testdata/tokenizer_golden_<id>.jsonl: the 1,000 source sentences and their token ids.

    python tools/model/gen_tokenizer_golden.py --model fr|multi

One JSON object per line: {"text": ..., "ids": [...]}, ids from the reference tokenizer of the pinned
revision, with the `<s> … </s>` template and no truncation. The Kotlin tokenizer must reproduce at least
99.5 % of the lines exactly (docs/specs/embeddings.md §3.3).
"""

from __future__ import annotations

import argparse
import json

import common
import runtime


def lines(source: common.ModelSource) -> list[str]:
    tok = runtime.tokenizer(source)
    return [json.dumps({"text": text, "ids": tok.encode(text).ids}, ensure_ascii=False) for text in runtime.read_sentences()]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--model", required=True, choices=sorted(common.MODELS))
    args = parser.parse_args()
    source = common.model(args.model)
    target = common.TESTDATA / f"tokenizer_golden_{source.id}.jsonl"
    target.parent.mkdir(parents=True, exist_ok=True)
    content = lines(source)
    target.write_text("\n".join(content) + "\n", encoding="utf-8")
    common.record_testdata(target)
    print(f"{source.id}: {len(content)} sentences -> {target.relative_to(common.REPO)}")


if __name__ == "__main__":
    main()
