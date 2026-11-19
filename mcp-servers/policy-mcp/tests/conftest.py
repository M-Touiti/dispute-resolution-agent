"""Shared fixtures: one PostgreSQL per test session, built like the compose database.

The container uses the compose image and the compose init scripts that matter here (the pgvector extension and the
``policy_service`` role). The app under test connects as ``policy_service`` and uses the deterministic hash
embedder; the retrieval-quality tests use the real model in a separate database.
"""

import socket
import threading
import time
from collections.abc import Iterator
from pathlib import Path

import psycopg
import pytest
import uvicorn
from testcontainers.community.postgres import PostgresContainer

from policy_mcp.app import MCP_PATH, Settings, create_app

INITDB = Path(__file__).parents[3] / "deploy" / "compose" / "postgres" / "initdb"
IMAGE = "pgvector/pgvector:0.8.7-pg18-trixie"  # same as deploy/compose/docker-compose.yml


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture(scope="session")
def postgres() -> Iterator[PostgresContainer]:
    container = PostgresContainer(IMAGE, username="disputes", password="disputes", dbname="disputes", driver=None)
    for script in ("00-extensions.sql", "30-policy-service.sh"):
        container.with_volume_mapping(str(INITDB / script), f"/docker-entrypoint-initdb.d/{script}")
    with container:
        yield container


def database_url(postgres: PostgresContainer, user: str, password: str, dbname: str = "disputes") -> str:
    host, port = postgres.get_container_host_ip(), postgres.get_exposed_port(5432)
    return f"postgresql://{user}:{password}@{host}:{port}/{dbname}"


@pytest.fixture(scope="session")
def service_url(postgres: PostgresContainer) -> str:
    return database_url(postgres, "policy_service", "policy_service")


@pytest.fixture(scope="session")
def scratch_database(postgres: PostgresContainer):
    """Creates an empty database prepared like ``disputes`` (pgvector, a ``policy`` schema owned by the service)."""

    def create(name: str) -> str:
        with psycopg.connect(database_url(postgres, "disputes", "disputes"), autocommit=True) as admin:
            admin.execute(f'CREATE DATABASE "{name}" OWNER policy_service')
        with psycopg.connect(database_url(postgres, "disputes", "disputes", name), autocommit=True) as admin:
            admin.execute("CREATE EXTENSION IF NOT EXISTS vector")
            admin.execute("CREATE SCHEMA policy AUTHORIZATION policy_service")
        return database_url(postgres, "policy_service", "policy_service", name)

    return create


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def base_url(service_url: str) -> Iterator[str]:
    """The real app under uvicorn on a free port, for tests over real Streamable HTTP."""
    port = _free_port()
    app = create_app(Settings(db_url=service_url, embedder="hash"))
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning"))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    deadline = time.monotonic() + 30
    while not server.started:
        if not thread.is_alive() or time.monotonic() > deadline:
            raise RuntimeError("policy-mcp did not start")
        time.sleep(0.05)
    yield f"http://127.0.0.1:{port}"
    server.should_exit = True
    thread.join(timeout=10)


@pytest.fixture(scope="session")
def mcp_url(base_url: str) -> str:
    return base_url + MCP_PATH
