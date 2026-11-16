"""FastAPI application: the MCP Streamable HTTP endpoint at ``/mcp`` and a health check at ``/health``.

Run with ``uv run uvicorn policy_mcp.app:app --port 8082``.
"""

import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from mcp.server.transport_security import TransportSecuritySettings

from . import __version__
from .server import build_server

MCP_PATH = "/mcp"

# DNS rebinding protection: only these Host headers are served. Compose adds the service name.
_DEFAULT_ALLOWED_HOSTS = "127.0.0.1:*,localhost:*,[::1]:*"


def _allowed_hosts() -> list[str]:
    raw = os.environ.get("POLICY_MCP_ALLOWED_HOSTS", _DEFAULT_ALLOWED_HOSTS)
    return [h.strip() for h in raw.split(",") if h.strip()]


def create_app() -> FastAPI:
    server = build_server()
    hosts = _allowed_hosts()
    mcp_app = server.streamable_http_app(
        streamable_http_path=MCP_PATH,
        transport_security=TransportSecuritySettings(
            enable_dns_rebinding_protection=True,
            allowed_hosts=hosts,
            allowed_origins=[f"http://{h}" for h in hosts],
        ),
    )

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        # A mounted Starlette app's lifespan does not run, so the session manager is started here.
        async with server.session_manager.run():
            yield

    app = FastAPI(
        title="policy-mcp", version=__version__, lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None
    )

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP"}

    app.mount("/", mcp_app)
    return app


app = create_app()
