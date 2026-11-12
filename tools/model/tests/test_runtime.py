"""Reference pooling of runtime.py (docs/specs/embeddings.md §4) and the quality checks."""

import common
import numpy as np
import runtime


class FakeInput:
    def __init__(self, name):
        self.name = name


class FakeSession:
    """Returns hidden states equal to the token ids, repeated over 384 dimensions."""

    def __init__(self, names):
        self.names = names
        self.feeds = None

    def get_inputs(self):
        return [FakeInput(n) for n in self.names]

    def run(self, _, feeds):
        self.feeds = feeds
        ids = feeds["input_ids"].astype(np.float32)
        return [np.repeat(ids[..., None], common.DIMENSIONS, axis=-1)]


def test_mean_pooling_then_l2_normalisation():
    vector = runtime.embed_ids(FakeSession(["input_ids", "attention_mask"]), [1, 2, 3])
    assert vector.shape == (common.DIMENSIONS,)
    assert np.isclose(np.linalg.norm(vector), 1.0)
    assert np.allclose(vector, 1 / np.sqrt(common.DIMENSIONS))


def test_token_type_ids_are_fed_only_when_declared():
    without = FakeSession(["input_ids", "attention_mask"])
    runtime.embed_ids(without, [5, 6])
    assert set(without.feeds) == {"input_ids", "attention_mask"}
    with_types = FakeSession(["input_ids", "attention_mask", "token_type_ids"])
    runtime.embed_ids(with_types, [5, 6])
    assert not with_types.feeds["token_type_ids"].any()


def test_prefixes_and_lengths_follow_the_spec():
    assert runtime.prefixed("query", "horaires") == "query: horaires"
    assert runtime.prefixed("passage", "texte") == "passage: texte"
    assert runtime.max_tokens("query") == 64
    assert runtime.max_tokens("passage") == 256
