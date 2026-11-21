from fastapi import APIRouter, Depends, Request
from fastapi.concurrency import run_in_threadpool

from app.audio import prepare_audio
from app.auth import require_auth
from app.errors import ProblemError
from app.faces import EmbedResponse, FaceEngineUnavailable, ImageDecodeError, embed_response
from app.profiles import build_profile
from app.profiles_schema import BuildRequest, Profile, RefreshRequest, RefreshResponse
from app.stt import transcribe
from app.transcript import Transcript
from app.uploads import read_upload

public = APIRouter()
v1 = APIRouter(prefix="/v1", dependencies=[Depends(require_auth)])


@public.get("/healthz")
async def healthz() -> dict[str, bool]:
    return {"ok": True}


@v1.post("/faces/embed")
async def faces_embed(request: Request) -> EmbedResponse:
    state = request.app.state
    image = await read_upload(request, "image", state.settings.max_image_bytes)
    try:
        detection = await run_in_threadpool(state.face_engine.detect, image)
    except ImageDecodeError:
        raise ProblemError(422, "invalid_image", "The image could not be decoded.") from None
    except FaceEngineUnavailable:
        raise ProblemError(
            503, "face_engine_unavailable", "The face model is not available on this server."
        ) from None
    return embed_response(detection)


@v1.post("/audio/transcribe")
async def audio_transcribe(request: Request) -> Transcript:
    state = request.app.state
    data = await read_upload(request, "audio", state.settings.max_audio_bytes)
    prepared = await prepare_audio(data, ffmpeg_timeout_s=state.settings.ffmpeg_timeout_s)
    del data
    return await transcribe(state.transcriber, prepared)


@v1.post("/profiles/build")
async def profiles_build(body: BuildRequest, request: Request) -> Profile:
    state = request.app.state
    return await build_profile(state.settings, state.chat, body)


@v1.post("/profiles/refresh")
async def profiles_refresh(body: RefreshRequest, request: Request) -> RefreshResponse:
    updates = request.app.state.refresh_fixture.get(body.name.strip().lower(), [])
    return RefreshResponse(since_you_met=updates)
