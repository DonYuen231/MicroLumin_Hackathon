FROM python:3.12-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    UV_COMPILE_BYTECODE=0 \
    UV_LINK_MODE=copy \
    PATH="/app/.venv/bin:$PATH" \
    FACE_MODEL_ROOT=/opt/insightface

# ffmpeg for audio normalization; build tools and OpenCV runtime libs for InsightFace.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg build-essential libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.13.9 /uv /usr/local/bin/uv

WORKDIR /app
COPY pyproject.toml uv.lock ./
# The "face" extra (InsightFace, onnxruntime, OpenCV) is installed only here.
RUN uv sync --frozen --no-dev --no-install-project --extra face

# Download the buffalo_l model pack at build time, not on the first request.
RUN python -c "from insightface.app import FaceAnalysis; FaceAnalysis(name='buffalo_l', root='/opt/insightface', allowed_modules=['detection','recognition'], providers=['CPUExecutionProvider'])"

COPY app ./app
COPY fixtures ./fixtures

RUN useradd --system --no-create-home appuser
USER appuser

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3)"

# 0.0.0.0 so the port is reachable from outside the container.
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
