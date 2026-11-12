"""Versioned source sentences and texts (ticket M0-10)."""

import json

import common
import gen_sentences


def test_committed_sentences_are_the_generator_output():
    committed = (common.DATA / "tokenizer_sentences.txt").read_text(encoding="utf-8")
    assert committed == "\n".join(gen_sentences.sentences()) + "\n", "run tools/model/gen_sentences.py"


def test_one_thousand_distinct_single_line_sentences():
    sentences = gen_sentences.sentences()
    assert len(sentences) == 1_000 == len(set(sentences))
    assert not any("\n" in s or "\r" in s for s in sentences)


def test_sentences_cover_the_tokenizer_edge_cases():
    text = "\n".join(gen_sentences.sentences())
    for sample in ("https://", "@example.", "€", "$", "😀", "‍", " ", "\t", "ﬁ", "²", "日本語", "CMD-2026-"):
        assert sample in text, sample


def test_embedding_texts_are_fifty_queries_and_passages():
    rows = [json.loads(line) for line in (common.DATA / "embedding_texts.jsonl").read_text(encoding="utf-8").splitlines()]
    assert rows == [{"kind": k, "text": t} for k, t in gen_sentences.EMBEDDING_TEXTS]
    assert len(rows) == 50
    assert {row["kind"] for row in rows} == {"query", "passage"}
