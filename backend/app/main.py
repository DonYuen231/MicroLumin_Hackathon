import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI
from fastapi.concurrency import run_in_threadpool

from app.config import Settings, load_settings
from app.errors import install_error_handlers
from app.faces import FaceEngine, InsightFaceEngine
from app.llm import ChatClient
from app.middleware import MULTIPART_OVERHEAD, BodyLimitMiddleware, RequestContextMiddleware
from app.profiles import load_refresh_fixture
from app.routes import public, v1
from app.stt import Transcriber, build_transcriber

log = logging.getLogger("recall")


def create_app(
    settings: Settings | None = None,
    *,
    http_client: httpx.AsyncClient | None = None,
    face_engine: FaceEngine | None = None,
    transcriber: Transcriber | None = None,
) -> FastAPI:
    """App factory. Run with: uvicorn app.main:create_app --factory"""
    settings = settings or load_settings()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    client = http_client or httpx.AsyncClient()
    chat = ChatClient(settings, client)
    engine = face_engine or InsightFaceEngine(settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if settings.preload_face_engine and isinstance(engine, InsightFaceEngine):
            await run_in_threadpool(engine.load)
            log.info("face model loaded")
        yield
        await client.aclose()

    app = FastAPI(
        title="Recall backend",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings
    app.state.face_engine = engine
    app.state.chat = chat
    app.state.transcriber = transcriber or build_transcriber(settings, client, chat)
    app.state.refresh_fixture = load_refresh_fixture(settings.refresh_fixture_path)

    install_error_handlers(app)
    app.include_router(public)
    app.include_router(v1)
    app.add_middleware(
        BodyLimitMiddleware,
        limits={
            "/v1/faces/embed": settings.max_image_bytes + MULTIPART_OVERHEAD,
            "/v1/audio/transcribe": settings.max_audio_bytes + MULTIPART_OVERHEAD,
        },
        default=settings.max_json_bytes,
    )
    app.add_middleware(RequestContextMiddleware)
    return app
