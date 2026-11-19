"""Hybrid retrieval: full-text and vector candidates merged by weighted reciprocal-rank fusion (RRF).

RRF scores a section by the sum of ``weight / (k + rank)`` over the candidate lists it appears in, so a section near
the top of either list ranks well and one in both ranks best. Full text counts half (ADR 0007): measured on the real
corpus, it helps exact terms ("3-D Secure", "120 days") but at full weight its any-word matches push paraphrased
questions down. Only ranks are used, never raw scores, and ties are broken
by section id: the same corpus and model always return the same order, which keeps replay fixtures stable.
"""

from dataclasses import dataclass
from typing import Literal

from .corpus import Section
from .embedding import Embedder
from .store import PolicyStore

RRF_K = 60
CANDIDATES = 20
WEIGHTS = {"full_text": 0.5, "semantic": 1.0}

Mode = Literal["hybrid", "full_text", "semantic"]


@dataclass(frozen=True, slots=True)
class Hit:
    section: Section
    matched_by: tuple[str, ...]


class Retriever:
    def __init__(self, store: PolicyStore, embedder: Embedder) -> None:
        self._store = store
        self._embedder = embedder

    def search(self, query: str, category: str | None, max_results: int, mode: Mode = "hybrid") -> list[Hit]:
        lists: dict[str, list[str]] = {}
        if mode in ("hybrid", "full_text"):
            lists["full_text"] = self._store.full_text(query, category, CANDIDATES)
        if mode in ("hybrid", "semantic"):
            vector = self._embedder.embed_query(query)
            lists["semantic"] = self._store.nearest(vector, category, CANDIDATES)

        scores: dict[str, float] = {}
        for name, ids in lists.items():
            for rank, section_id in enumerate(ids, start=1):
                scores[section_id] = scores.get(section_id, 0.0) + WEIGHTS[name] / (RRF_K + rank)
        ranked = sorted(scores, key=lambda sid: (-scores[sid], sid))[:max_results]

        sections = self._store.sections(ranked)
        return [
            Hit(sections[sid], tuple(name for name, ids in lists.items() if sid in ids))
            for sid in ranked
            if sid in sections
        ]

    def section(self, section_id: str) -> Section | None:
        return self._store.sections([section_id]).get(section_id)
