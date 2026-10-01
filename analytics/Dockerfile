# syntax=docker/dockerfile:1

# 상주 서비스가 아니라 배치다. compose에서 `docker compose run --rm analytics <command>`로 돌린다
# (analytics/cron.example).
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /usr/local/bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    PATH=/opt/venv/bin:$PATH

WORKDIR /app
# 의존성 레이어를 소스와 분리해 소스만 바뀌면 다시 받지 않게 한다. 버전은 uv.lock 그대로다.
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev --no-install-project
COPY src ./src
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev

RUN useradd --system --create-home --shell /usr/sbin/nologin wio
USER wio
ENTRYPOINT ["wio-analytics"]
CMD ["--help"]
