import asyncio

import pytest

from app.errors import ProblemError
from app.middleware import BodyLimitMiddleware
from tests.conftest import AUTH, face, make_wav
from tests.test_auth_errors import assert_problem


def test_image_over_2mb_rejected(client, engine):
    big = b"\xff" * (2 * 1024 * 1024 + 1)
    resp = client.post("/v1/faces/embed", headers=AUTH, files={"image": ("a.jpg", big)})
    assert_problem(resp, 413, "payload_too_large")
    assert engine.calls == 0


def test_image_at_limit_accepted(client, engine):
    engine.detection.faces.append(face([1, 1, 5, 5]))
    ok = b"\xff" * (2 * 1024 * 1024)
    resp = client.post("/v1/faces/embed", headers=AUTH, files={"image": ("a.jpg", ok)})
    assert resp.status_code == 200


def test_audio_over_limit_rejected(make_client):
    client = make_client(max_audio_bytes=1000)
    resp = client.post(
        "/v1/audio/transcribe", headers=AUTH, files={"audio": ("a.wav", make_wav(1.0))}
    )
    assert_problem(resp, 413, "payload_too_large")


def test_json_body_limit(make_client):
    client = make_client(max_json_bytes=200)
    resp = client.post(
        "/v1/profiles/refresh", headers=AUTH, json={"person_id": "p" * 500, "name": "x"}
    )
    assert_problem(resp, 413, "payload_too_large")


def _run_middleware(receive, limit, headers):
    async def app(scope, receive, send):
        while True:
            msg = await receive()
            if not msg.get("more_body"):
                return

    scope = {"type": "http", "path": "/p", "headers": headers}
    mw = BodyLimitMiddleware(app, limits={"/p": limit}, default=limit)
    asyncio.run(mw(scope, receive, None))


def test_content_length_over_limit_rejected_without_reading():
    reads = 0

    async def receive():
        nonlocal reads
        reads += 1
        return {"type": "http.request", "body": b"x", "more_body": True}

    with pytest.raises(ProblemError) as exc:
        _run_middleware(receive, 100, [(b"content-length", b"999999")])
    assert exc.value.status_code == 413
    assert reads == 0


def test_streaming_body_aborts_at_limit_without_reading_everything():
    chunks_read = 0
    limit = 10 * 1024

    async def receive():
        nonlocal chunks_read
        chunks_read += 1
        # Endless stream with no Content-Length: a lying or chunked client.
        return {"type": "http.request", "body": b"x" * 1024, "more_body": True}

    with pytest.raises(ProblemError) as exc:
        _run_middleware(receive, limit, [])
    assert exc.value.status_code == 413
    assert chunks_read == limit // 1024 + 1


def test_non_multipart_rejected(client):
    resp = client.post("/v1/faces/embed", headers=AUTH, json={"image": "x"})
    assert_problem(resp, 415, "unsupported_media_type")


def test_missing_and_empty_field(client):
    r1 = client.post("/v1/faces/embed", headers=AUTH, files={"other": ("a.jpg", b"abc")})
    assert_problem(r1, 422, "validation_error")
    r2 = client.post("/v1/faces/embed", headers=AUTH, files={"image": ("a.jpg", b"")})
    assert_problem(r2, 422, "validation_error")
