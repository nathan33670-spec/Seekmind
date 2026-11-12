"""Reference tokenization and inference, in Python (docs/specs/embeddings.md §1, §3, §4).

The Kotlin tokenizer and OrtEmbeddingEngine are tested against what this module produces.
"""

from __future__ import annotations

from pathlib import Path

import common
import numpy as np


def tokenizer(source: common.ModelSource, max_tokens: int | None = None):
    """The reference tokenizer of the model: `<s> … </s>`, no padding, truncation at `max_tokens`."""
    from tokenizers import Tokenizer

    instance = Tokenizer.from_file(str(common.fetch(source, source.tokenizer)))
    instance.no_padding()
    if max_tokens is None:
        instance.no_truncation()
    else:
        instance.enable_truncation(max_length=max_tokens)
    return instance


def session(model_path: Path):
    import onnxruntime as ort

    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(model_path), options, providers=["CPUExecutionProvider"])


def embed_ids(onnx_session, ids: list[int]) -> np.ndarray:
    """Masked mean of last_hidden_state, then L2 normalisation, for one sequence."""
    input_ids = np.asarray([ids], dtype=np.int64)
    feeds = {"input_ids": input_ids, "attention_mask": np.ones_like(input_ids)}
    declared = {i.name for i in onnx_session.get_inputs()}
    if "token_type_ids" in declared:
        feeds["token_type_ids"] = np.zeros_like(input_ids)
    hidden = onnx_session.run(None, {k: v for k, v in feeds.items() if k in declared})[0][0]
    vector = hidden.mean(axis=0)
    return vector / np.linalg.norm(vector)


def prefixed(kind: str, text: str) -> str:
    return common.PREFIXES[kind] + text


def max_tokens(kind: str) -> int:
    return common.MAX_QUERY_TOKENS if kind == "query" else common.MAX_PASSAGE_TOKENS


def read_sentences() -> list[str]:
    path = common.DATA / "tokenizer_sentences.txt"
    return [line for line in path.read_text(encoding="utf-8").split("\n") if line]
