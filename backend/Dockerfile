FROM python:3.12-slim AS runtime

ARG APP_UID=1000
ARG APP_GID=1000

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app
RUN groupadd --gid "${APP_GID}" tornado \
    && useradd --uid "${APP_UID}" --gid tornado --home-dir /app --no-create-home tornado
COPY pyproject.toml ./
COPY app ./app
RUN pip install --upgrade pip && pip install .
COPY . .
RUN mkdir -p /app/data && chown -R tornado:tornado /app
USER tornado
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/readyz', timeout=3)"
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers --forwarded-allow-ips=${FORWARDED_ALLOW_IPS:-127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16}"]
