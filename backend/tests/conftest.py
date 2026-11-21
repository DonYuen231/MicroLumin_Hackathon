"""Shared fixtures. Nothing here reads .env or touches the network."""

import io
import json
import wave
from collections.abc import Callable

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.faces import DetectedFace, Detection
from app.main import create_app

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
LLM_URL = "https://llm.test/v1/chat/completions"


def make_settings(**overrides) -> Settings:
    values = {
        "app_token": TOKEN,
        "nanogpt_api_key": "nano-key",
        "nanogpt_base_url": "https://llm.test/v1",
        "deepgram_api_key": "dg-key",
        "deepgram_base_url": "https://dg.test",
        "upstream_backoff_s": 0,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


def make_wav(seconds: float = 2.0, rate: int = 16000, channels: int = 1) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"\x00\x00" * int(seconds * rate) * channels)
    return buf.getvalue()


def chat_reply(content: str) -> httpx.Response:
    return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})


class Upstream:
    """Scripted replies for outside HTTP calls. Records every request."""

    def __init__(self) -> None:
        self.requests: list[httpx.Request] = []
        self.queue: list = []

    def reply(self, *items) -> None:
        self.queue.extend(items)

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if not self.queue:
            raise AssertionError("unexpected outside call")
        item = self.queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    def json(self, index: int = -1) -> dict:
        return json.loads(self.requests[index].content)


class FakeEngine:
    def __init__(self) -> None:
        self.detection = Detection(width=1000, height=800, faces=[])
        self.error: Exception | None = None
        self.calls = 0

    def detect(self, image_bytes: bytes) -> Detection:
        self.calls += 1
        if self.error:
            raise self.error
        return self.detection


def face(bbox, score=0.99, emb=None) -> DetectedFace:
    return DetectedFace(
        bbox=tuple(bbox), det_score=score, embedding=emb or [3.0, 4.0] + [0.0] * 510
    )


@pytest.fixture
def upstream() -> Upstream:
    return Upstream()


@pytest.fixture
def engine() -> FakeEngine:
    return FakeEngine()


@pytest.fixture
def make_client(upstream, engine) -> Callable[..., TestClient]:
    def _make(**overrides) -> TestClient:
        http = httpx.AsyncClient(transport=httpx.MockTransport(upstream.handler))
        app = create_app(make_settings(**overrides), http_client=http, face_engine=engine)
        return TestClient(app, raise_server_exceptions=False)

    return _make


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client()
