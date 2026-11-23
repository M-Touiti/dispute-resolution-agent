"""FastAPI application: the MCP Streamable HTTP endpoint at ``/mcp`` and a health check at ``/health``.

Run with ``uv run uvicorn --factory policy_mcp.app:create_app --port 8082``. At start-up the corpus is ingested into
PostgreSQL (skipped when the corpus and the embedding model are unchanged); the server answers only after that.
"""

import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path

import anyio
from fastapi import FastAPI, Response
from mcp.server.transport_security import TransportSecuritySettings
from psycopg_pool import ConnectionPool

from . import __version__
from .corpus import fingerprint, read_corpus
from .embedding import Embedder, FastEmbedEmbedder, HashEmbedder
from .metrics import exposition
from .retrieval import Retriever
from .server import build_server
from .store import PolicyStore

log = logging.getLogger(__name__)

MCP_PATH = "/mcp"


def repository_corpus_dir() -> Path:
    """``data/policy-corpus`` in a repository checkout. Containers set ``POLICY_CORPUS_DIR`` instead."""
    return Path(__file__).resolve().parents[3] / "data" / "policy-corpus"


def _list(value: str) -> list[str]:
    return [h.strip() for h in value.split(",") if h.strip()]


@dataclass(frozen=True)
class Settings:
    db_url: str = "postgresql://policy_service:policy_service@localhost:5432/disputes"
    corpus_dir: Path = field(default_factory=repository_corpus_dir)
    embedder: str = "fastembed"
    """``fastembed`` (production) or ``hash`` (tests only)."""
    # DNS rebinding protection: only these Host headers are served. Compose adds the service name.
    allowed_hosts: list[str] = field(default_factory=lambda: ["127.0.0.1:*", "localhost:*", "[::1]:*"])

    @classmethod
    def from_env(cls) -> "Settings":
        defaults = cls(corpus_dir=Path())
        return cls(
            db_url=os.environ.get("POLICY_DB_URL", defaults.db_url),
            corpus_dir=Path(os.environ["POLICY_CORPUS_DIR"])
            if "POLICY_CORPUS_DIR" in os.environ
            else repository_corpus_dir(),
            embedder=os.environ.get("POLICY_EMBEDDER", defaults.embedder),
            allowed_hosts=_list(os.environ["POLICY_MCP_ALLOWED_HOSTS"])
            if "POLICY_MCP_ALLOWED_HOSTS" in os.environ
            else defaults.allowed_hosts,
        )


def _embedder(name: str) -> Embedder:
    if name == "fastembed":
        return FastEmbedEmbedder()
    if name == "hash":
        return HashEmbedder()
    raise ValueError(f"unknown POLICY_EMBEDDER {name!r}: use fastembed or hash")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    embedder = _embedder(settings.embedder)
    pool = ConnectionPool(settings.db_url, min_size=1, max_size=5, open=False, name="policy")
    store = PolicyStore(pool)
    server = build_server(Retriever(store, embedder))
    mcp_app = server.streamable_http_app(
        streamable_http_path=MCP_PATH,
        transport_security=TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=settings.allowed_hosts,
            allowed_origins=[f"http://{h}" for h in settings.allowed_hosts],
        ),
    )
    state: dict[str, object] = {}

    def ingest() -> None:
        sections = read_corpus(settings.corpus_dir)
        result = store.ingest(sections, fingerprint(settings.corpus_dir), embedder)
        state.update(sections=result.sections, embedding_model=result.model_id, corpus_sha256=result.corpus_sha256)
        log.info(
            "policy corpus: %d sections, %s (%s)",
            result.sections,
            result.model_id,
            "ingested" if result.changed else "unchanged",
        )

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        pool.open(wait=True, timeout=30)
        try:
            await anyio.to_thread.run_sync(ingest)
            # A mounted Starlette app's lifespan does not run, so the session manager is started here.
            async with server.session_manager.run():
                yield
        finally:
            pool.close()

    app = FastAPI(
        title="policy-mcp", version=__version__, lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None
    )

    @app.get("/health")
    async def health() -> dict[str, object]:
        return {"status": "UP", **state}

    @app.get("/metrics")
    async def metrics() -> Response:
        body, content_type = exposition()
        return Response(content=body, media_type=content_type)

    app.mount("/", mcp_app)
    return app
