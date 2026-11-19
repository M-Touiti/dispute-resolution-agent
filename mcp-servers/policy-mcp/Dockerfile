# syntax=docker/dockerfile:1
# Build context: mcp-servers/policy-mcp. Tests run in CI, not in the image build.
FROM python:3.15.2-slim-trixie
COPY --from=ghcr.io/astral-sh/uv:0.11.33 /uv /bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /app
COPY pyproject.toml uv.lock .python-version ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev
COPY policy_mcp/ policy_mcp/
ENV PATH=/app/.venv/bin:$PATH FASTEMBED_CACHE_PATH=/app/models
# The embedding model is part of the image: downloaded once here, checked against the pinned digest (ADR 0007).
RUN python -m policy_mcp.embedding --bake /app/models
RUN useradd --system --uid 10001 --no-create-home app
USER app
# No model download at run time; the corpus is mounted at /corpus (see deploy/compose/docker-compose.yml).
ENV HF_HUB_OFFLINE=1 POLICY_CORPUS_DIR=/corpus
EXPOSE 8082
CMD ["uvicorn", "--factory", "policy_mcp.app:create_app", "--host", "0.0.0.0", "--port", "8082"]
