# syntax=docker/dockerfile:1
# Build context: performance/shim. The load-test shim (task 2.13); compose profile "perf".
FROM python:3.13.18-slim-trixie
COPY --from=ghcr.io/astral-sh/uv:0.11.33 /uv /bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /app
COPY pyproject.toml uv.lock .python-version ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev
COPY shim/ shim/
ENV PATH=/app/.venv/bin:$PATH
RUN useradd --system --uid 10001 --no-create-home app
USER app
EXPOSE 8090
CMD ["uvicorn", "--factory", "shim.app:create_app", "--host", "0.0.0.0", "--port", "8090", "--log-level", "warning"]
