# syntax=docker/dockerfile:1
# Build context: mcp-servers/policy-mcp. Tests run in CI, not in the image build.
FROM python:3.13.18-slim-trixie
COPY --from=ghcr.io/astral-sh/uv:0.11.33 /uv /bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /app
COPY pyproject.toml uv.lock .python-version ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev
COPY policy_mcp/ policy_mcp/
RUN useradd --system --uid 10001 --no-create-home app
USER app
ENV PATH=/app/.venv/bin:$PATH
EXPOSE 8082
CMD ["uvicorn", "policy_mcp.app:app", "--host", "0.0.0.0", "--port", "8082"]
