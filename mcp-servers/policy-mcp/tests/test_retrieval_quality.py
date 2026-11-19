"""Retrieval quality on the real corpus with the production embedding model (ADR 0007).

``retrieval_questions.json`` holds two sets: ``paraphrase`` (a cardholder's words, avoiding the policy's) and
``agent`` (the investigator's queries, in ledger and policy vocabulary). Each set has its own floor, one or two
questions below what was measured, so a real regression fails CI and noise does not. ``POLICY_QUALITY_REPORT=1``
prints every mode for every set, and ``model2vec`` too when installed (``uv run --with model2vec==0.10.0 ...``).
"""

import json
import os
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

import pytest
from psycopg_pool import ConnectionPool

from policy_mcp.app import repository_corpus_dir
from policy_mcp.corpus import fingerprint, read_corpus
from policy_mcp.embedding import Embedder, FastEmbedEmbedder
from policy_mcp.retrieval import Mode, Retriever
from policy_mcp.store import PolicyStore

QUESTIONS = json.loads((Path(__file__).parent / "retrieval_questions.json").read_text(encoding="utf-8"))["questions"]
CORPUS = repository_corpus_dir()

# Measured 19 Nov 2026, bge-small-en-v1.5, hybrid with full text at 0.5 (docs/adr/0007):
#   paraphrase (30): R@1 0.50, R@3 0.80, MRR 0.65     agent (15): R@1 0.93, R@3 1.00, MRR 0.96
FLOORS = {
    "paraphrase": {"recall_at_1": 0.43, "recall_at_3": 0.73},
    "agent": {"recall_at_1": 0.86, "recall_at_3": 0.93},
}


@dataclass(frozen=True)
class Quality:
    recall_at_1: float
    recall_at_3: float
    mrr: float
    misses: tuple[str, ...]


def evaluate(retriever: Retriever, mode: Mode, question_set: str) -> Quality:
    questions = [q for q in QUESTIONS if q["set"] == question_set]
    ranks, misses = [], []
    for q in questions:
        ids = [h.section.section_id for h in retriever.search(q["question"], q.get("category"), 10, mode)]
        rank = ids.index(q["expected"]) + 1 if q["expected"] in ids else None
        ranks.append(rank)
        if rank != 1:
            misses.append(f"{q['expected']} at {rank}: {q['question']}")
    n = len(ranks)
    return Quality(
        recall_at_1=sum(r == 1 for r in ranks) / n,
        recall_at_3=sum(r is not None and r <= 3 for r in ranks) / n,
        mrr=sum(1 / r for r in ranks if r) / n,
        misses=tuple(misses),
    )


def _retriever(url: str, embedder: Embedder) -> tuple[ConnectionPool, Retriever]:
    pool = ConnectionPool(url, min_size=1, max_size=2, open=True)
    store = PolicyStore(pool)
    store.ingest(read_corpus(CORPUS), fingerprint(CORPUS), embedder)
    return pool, Retriever(store, embedder)


@pytest.fixture(scope="module")
def production_retriever(scratch_database):
    pool, retriever = _retriever(scratch_database("quality_production"), FastEmbedEmbedder())
    with pool:
        yield retriever


def test_both_question_sets_cover_the_corpus() -> None:
    sets = {q["set"] for q in QUESTIONS}
    assert sets == set(FLOORS)
    ids = {s.section_id for s in read_corpus(CORPUS)}
    assert {q["expected"] for q in QUESTIONS} <= ids


@pytest.mark.parametrize("question_set", sorted(FLOORS))
def test_hybrid_retrieval_meets_the_floor(production_retriever: Retriever, question_set: str) -> None:
    quality = evaluate(production_retriever, "hybrid", question_set)
    floor = FLOORS[question_set]

    assert quality.recall_at_3 >= floor["recall_at_3"], quality.misses
    assert quality.recall_at_1 >= floor["recall_at_1"], quality.misses


class Model2VecEmbedder:
    """Measurement only (ADR 0004's fallback); not shipped."""

    model_id = "model2vec/minishlab/potion-base-8M"
    dimension = 256

    def __init__(self) -> None:
        from model2vec import StaticModel

        self._model = StaticModel.from_pretrained("minishlab/potion-base-8M")

    def embed(self, texts: Sequence[str]) -> list[list[float]]:
        import numpy as np

        v = self._model.encode(list(texts))
        return (v / np.linalg.norm(v, axis=1, keepdims=True)).tolist()

    def embed_query(self, text: str) -> list[float]:
        return self.embed([text])[0]


@pytest.mark.skipif(os.environ.get("POLICY_QUALITY_REPORT") != "1", reason="set POLICY_QUALITY_REPORT=1 to print")
def test_print_the_comparison(production_retriever: Retriever, scratch_database) -> None:
    retrievers = [("bge-small hybrid", production_retriever)]
    try:
        pool, m2v = _retriever(scratch_database("quality_model2vec"), Model2VecEmbedder())
        retrievers.append(("model2vec", m2v))
    except ImportError:
        pool = None
    try:
        for name, retriever in retrievers:
            for mode in ("hybrid", "semantic", "full_text"):
                cells = []
                for question_set in sorted(FLOORS):
                    q = evaluate(retriever, mode, question_set)
                    cells.append(f"{question_set} R@1 {q.recall_at_1:.2f} R@3 {q.recall_at_3:.2f} MRR {q.mrr:.2f}")
                print(f"\n{name.split()[0]:10} {mode:10} " + " | ".join(cells), end="")
        print()
    finally:
        if pool is not None:
            pool.close()
