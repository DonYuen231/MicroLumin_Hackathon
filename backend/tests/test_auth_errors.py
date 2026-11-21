import hmac

import pytest

from app.config import load_settings
from tests.conftest import AUTH, TOKEN

PROBLEM_KEYS = {"type", "title", "status", "detail"}


def assert_problem(resp, status, title):
    assert resp.status_code == status
    assert resp.headers["content-type"].startswith("application/problem+json")
    body = resp.json()
    assert body.keys() >= PROBLEM_KEYS
    assert body["status"] == status
    assert body["title"] == title
    assert isinstance(body["detail"], str)
    return body


def test_healthz_needs_no_auth(client):
    resp = client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json() == {"ok": True}
    assert "x-request-id" in resp.headers


@pytest.mark.parametrize(
    "headers",
    [
        {},
        {"Authorization": "Bearer wrong"},
        {"Authorization": "Bearer "},
        {"Authorization": TOKEN},
        {"Authorization": f"Basic {TOKEN}"},
        {"Authorization": f"Bearer {TOKEN}x"},
        {"Authorization": f"Bearer {TOKEN[:-1]}"},
    ],
)
@pytest.mark.parametrize(
    "path",
    ["/v1/faces/embed", "/v1/audio/transcribe", "/v1/profiles/build", "/v1/profiles/refresh"],
)
def test_auth_required(client, path, headers):
    resp = client.post(path, headers=headers, json={})
    body = assert_problem(resp, 401, "unauthorized")
    assert resp.headers["www-authenticate"] == "Bearer"
    assert TOKEN not in str(body)


def test_auth_checked_before_body_validation(client):
    assert_problem(client.post("/v1/profiles/build", json={"bad": 1}), 401, "unauthorized")


def test_scheme_is_case_insensitive(client):
    resp = client.post(
        "/v1/profiles/refresh",
        headers={"Authorization": f"bearer {TOKEN}"},
        json={"person_id": "p", "name": "x"},
    )
    assert resp.status_code == 200


def test_token_compared_with_compare_digest(monkeypatch, client):
    calls = []
    real = hmac.compare_digest

    def spy(a, b):
        calls.append(1)
        return real(a, b)

    monkeypatch.setattr(hmac, "compare_digest", spy)
    client.post("/v1/profiles/refresh", headers=AUTH, json={"person_id": "p", "name": "x"})
    assert calls


def test_validation_error_is_problem_and_does_not_echo_input(client):
    resp = client.post(
        "/v1/profiles/build", headers=AUTH, json={"wearer_name": "SECRET-VALUE", "transcript": 5}
    )
    body = assert_problem(resp, 422, "validation_error")
    assert "SECRET-VALUE" not in resp.text
    assert body["errors"]
    assert {"loc", "msg"} <= body["errors"][0].keys()


def test_malformed_json_is_problem(client):
    resp = client.post(
        "/v1/profiles/build",
        headers={**AUTH, "Content-Type": "application/json"},
        content=b"{not json",
    )
    assert_problem(resp, 422, "validation_error")


def test_unknown_path_404_is_problem(client):
    assert_problem(client.get("/nope"), 404, "not_found")
    assert_problem(client.get("/v1/tts", headers=AUTH), 404, "not_found")


def test_wrong_method_405_is_problem(client):
    assert_problem(client.get("/v1/faces/embed", headers=AUTH), 405, "method_not_allowed")


def test_unhandled_exception_is_500_problem(client, engine):
    engine.error = RuntimeError("boom with secret details")
    resp = client.post(
        "/v1/faces/embed", headers=AUTH, files={"image": ("a.jpg", b"x", "image/jpeg")}
    )
    assert_problem(resp, 500, "internal_error")
    assert "boom" not in resp.text


def test_docs_are_not_exposed(client):
    assert_problem(client.get("/docs"), 404, "not_found")
    assert_problem(client.get("/openapi.json"), 404, "not_found")


def test_startup_fails_clearly_without_app_token(monkeypatch):
    monkeypatch.delenv("APP_TOKEN", raising=False)
    with pytest.raises(RuntimeError, match="APP_TOKEN"):
        load_settings(env_file=None)


def test_startup_fails_on_empty_app_token(monkeypatch):
    monkeypatch.setenv("APP_TOKEN", "")
    with pytest.raises(RuntimeError, match="APP_TOKEN"):
        load_settings(env_file=None)


def test_settings_defaults(monkeypatch):
    monkeypatch.setenv("APP_TOKEN", "t")
    for name in ("NANOGPT_BASE_URL", "LLM_MODEL", "STT_MODEL", "STT_PROVIDER"):
        monkeypatch.delenv(name, raising=False)
    s = load_settings(env_file=None)
    assert s.nanogpt_base_url == "https://nano-gpt.com/api/subscription/v1"
    assert s.llm_model == "z-ai/glm-5.3"
    assert s.stt_model == "xiaomi/mimo-v2.6-pro"
    assert s.stt_provider == "nanogpt"
    assert s.max_image_bytes == 2 * 1024 * 1024
    assert s.max_audio_bytes == 50 * 1024 * 1024
