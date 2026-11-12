"""Format of vocab.bin (docs/specs/embeddings.md §3.2)."""

import struct

import common
import pytest

PIECES = [("<s>", 0.0), ("<pad>", 0.0), ("</s>", 0.0), ("<unk>", 0.0), ("▁le", -3.5), ("é", -9.25), ("🙂", -12.0)]


def test_layout_is_magic_count_then_length_bytes_score():
    blob = common.encode_vocab([("▁a", -1.5)])
    assert blob[:4] == b"SMV1"
    assert struct.unpack_from("<i", blob, 4) == (1,)
    assert struct.unpack_from("<H", blob, 8) == (len("▁a".encode()),)
    assert blob[10:14] == "▁a".encode()
    assert struct.unpack_from("<f", blob, 14) == (-1.5,)
    assert len(blob) == 18


def test_round_trip_keeps_order_text_and_scores():
    assert common.decode_vocab(common.encode_vocab(PIECES)) == PIECES


def test_bad_magic_is_rejected():
    with pytest.raises(ValueError, match="magic"):
        common.decode_vocab(b"XXXX" + common.encode_vocab(PIECES)[4:])


def test_trailing_bytes_are_rejected():
    with pytest.raises(ValueError, match="trailing"):
        common.decode_vocab(common.encode_vocab(PIECES) + b"\x00")


def test_unigram_pieces_requires_the_special_tokens_in_place():
    tokenizer = {"model": {"type": "Unigram", "unk_id": 3, "vocab": [[p, s] for p, s in PIECES]}}
    assert common.unigram_pieces(tokenizer) == PIECES
    swapped = {"model": {"type": "Unigram", "unk_id": 3, "vocab": [[p, s] for p, s in [PIECES[1], PIECES[0], *PIECES[2:]]]}}
    with pytest.raises(ValueError, match="special tokens"):
        common.unigram_pieces(swapped)
    with pytest.raises(ValueError, match="Unigram"):
        common.unigram_pieces({"model": {"type": "BPE", "vocab": {}}})
