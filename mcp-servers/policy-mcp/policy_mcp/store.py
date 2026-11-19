"""The policy schema in PostgreSQL: ingestion, full-text and vector candidates, and section lookup.

Ingestion is idempotent: the corpus fingerprint and the embedding model id are stored with the sections, and the
table is rebuilt (in one transaction, under an advisory lock) only when either changes. A change of model can
change the vector dimension, so the table is dropped and recreated rather than updated.
"""

import re
from collections.abc import Sequence
from dataclasses import dataclass

from psycopg_pool import ConnectionPool

from .corpus import ALL_CATEGORIES, Section
from .embedding import Embedder

_LOCK_ID = 0x5041_4C49  # pg_advisory_xact_lock key for ingestion


@dataclass(frozen=True, slots=True)
class Ingestion:
    changed: bool
    sections: int
    corpus_sha256: str
    model_id: str


def _vector(values: Sequence[float]) -> str:
    return "[" + ",".join(f"{x:.7f}" for x in values) + "]"


class PolicyStore:
    def __init__(self, pool: ConnectionPool) -> None:
        self._pool = pool

    def ingest(self, sections: Sequence[Section], corpus_sha256: str, embedder: Embedder) -> Ingestion:
        with self._pool.connection() as conn, conn.transaction():
            conn.execute("SELECT pg_advisory_xact_lock(%s)", (_LOCK_ID,))
            conn.execute(
                """CREATE TABLE IF NOT EXISTS policy.corpus_state (
                       id INT PRIMARY KEY CHECK (id = 1),
                       corpus_sha256 TEXT NOT NULL,
                       embedding_model TEXT NOT NULL,
                       sections INT NOT NULL,
                       ingested_at TIMESTAMPTZ NOT NULL DEFAULT now())"""
            )
            current = conn.execute("SELECT corpus_sha256, embedding_model FROM policy.corpus_state").fetchone()
            if current == (corpus_sha256, embedder.model_id):
                return Ingestion(False, len(sections), corpus_sha256, embedder.model_id)

            vectors = embedder.embed([f"{s.title}\n\n{s.text}" for s in sections])
            conn.execute("DROP TABLE IF EXISTS policy.sections")
            conn.execute(
                f"""CREATE TABLE policy.sections (
                        section_id TEXT PRIMARY KEY,
                        title TEXT NOT NULL,
                        version INT NOT NULL,
                        applies_to TEXT[] NOT NULL,
                        body TEXT NOT NULL,
                        source_file TEXT NOT NULL,
                        search TSVECTOR GENERATED ALWAYS AS (
                            setweight(to_tsvector('english', title), 'A') ||
                            setweight(to_tsvector('english', body), 'B')) STORED,
                        embedding vector({int(embedder.dimension)}) NOT NULL)"""
            )
            conn.execute("CREATE INDEX sections_search ON policy.sections USING GIN (search)")
            with conn.cursor() as cur:
                cur.executemany(
                    """INSERT INTO policy.sections
                           (section_id, title, version, applies_to, body, source_file, embedding)
                       VALUES (%s, %s, %s, %s, %s, %s, %s::vector)""",
                    [
                        (s.section_id, s.title, s.version, list(s.applies_to), s.text, s.source_file, _vector(v))
                        for s, v in zip(sections, vectors, strict=True)
                    ],
                )
            conn.execute(
                """INSERT INTO policy.corpus_state (id, corpus_sha256, embedding_model, sections)
                   VALUES (1, %s, %s, %s)
                   ON CONFLICT (id) DO UPDATE SET corpus_sha256 = EXCLUDED.corpus_sha256,
                       embedding_model = EXCLUDED.embedding_model, sections = EXCLUDED.sections,
                       ingested_at = now()""",
                (corpus_sha256, embedder.model_id, len(sections)),
            )
            return Ingestion(True, len(sections), corpus_sha256, embedder.model_id)

    def full_text(self, query: str, category: str | None, limit: int) -> list[str]:
        """Section ids matching any word of the query, best first (ties by id)."""
        words = re.findall(r"[a-z0-9]+", query.lower())
        if not words:
            return []
        with self._pool.connection() as conn:
            rows = conn.execute(
                """SELECT section_id
                   FROM policy.sections, to_tsquery('english', %(q)s) q
                   WHERE search @@ q AND (%(cat)s::text IS NULL OR %(all)s = ANY(applies_to)
                                          OR %(cat)s = ANY(applies_to))
                   ORDER BY ts_rank_cd(search, q) DESC, section_id
                   LIMIT %(limit)s""",
                {"q": " | ".join(words), "cat": category, "all": ALL_CATEGORIES, "limit": limit},
            ).fetchall()
        return [r[0] for r in rows]

    def nearest(self, vector: Sequence[float], category: str | None, limit: int) -> list[str]:
        """Section ids by cosine distance to the query vector, nearest first (ties by id). Exact scan, no index."""
        with self._pool.connection() as conn:
            rows = conn.execute(
                """SELECT section_id
                   FROM policy.sections
                   WHERE %(cat)s::text IS NULL OR %(all)s = ANY(applies_to) OR %(cat)s = ANY(applies_to)
                   ORDER BY embedding <=> %(v)s::vector, section_id
                   LIMIT %(limit)s""",
                {"v": _vector(vector), "cat": category, "all": ALL_CATEGORIES, "limit": limit},
            ).fetchall()
        return [r[0] for r in rows]

    def sections(self, section_ids: Sequence[str]) -> dict[str, Section]:
        with self._pool.connection() as conn:
            rows = conn.execute(
                """SELECT section_id, title, version, applies_to, body, source_file
                   FROM policy.sections WHERE section_id = ANY(%s)""",
                (list(section_ids),),
            ).fetchall()
        return {r[0]: Section(r[0], r[1], r[2], tuple(r[3]), r[4], r[5]) for r in rows}
