"""Tests of the generator of the search evaluation set (ticket M5-07)."""

from __future__ import annotations

import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import gen_corpus  # noqa: E402


def test_the_set_is_deterministic_and_matches_testdata():
    first = gen_corpus.render(*gen_corpus.build())
    second = gen_corpus.render(*gen_corpus.build())
    assert first == second
    corpus_text, queries_text = first
    assert (gen_corpus.OUT / "corpus.jsonl").read_text(encoding="utf-8") == corpus_text, \
        "testdata/eval is stale: run python tools/eval/gen_corpus.py"
    assert (gen_corpus.OUT / "queries.jsonl").read_text(encoding="utf-8") == queries_text


def test_the_corpus_has_the_planned_shape():
    corpus, _, queries = gen_corpus.build()
    assert 280 <= len(corpus) <= 320
    languages = Counter(item["lang"] for item in corpus)
    assert 0.6 <= languages["fr"] / len(corpus) <= 0.72
    assert len({item["package"] for item in corpus}) == 15
    days = {(gen_corpus.NOW.timestamp() * 1000 - item["lastSeenAt"]) // 86_400_000 for item in corpus}
    assert max(days) < gen_corpus.DAYS
    assert 75 <= len(queries) <= 85
    assert {q["type"] for q in queries} == {"exact", "code", "paraphrase", "temporal", "application"}
    assert {q["lang"] for q in queries} == {"fr", "en", "bilingual"}


def test_every_query_points_at_an_anchor():
    corpus, _, queries = gen_corpus.build()
    anchors = {item["key"] for item in corpus if not item["key"].startswith("filler-")}
    for query in queries:
        assert query["expected"] and set(query["expected"]) <= anchors, query["id"]
    assert len({q["id"] for q in queries}) == len(queries)


def test_code_queries_appear_verbatim_in_their_capture():
    corpus, _, queries = gen_corpus.build()
    text = {item["key"]: item["text"] for item in corpus}
    for query in (q for q in queries if q["type"] == "code"):
        assert all(query["text"] in text[key] for key in query["expected"]), query["id"]
