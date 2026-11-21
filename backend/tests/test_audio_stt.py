import asyncio
import base64
import json
import pathlib
import shutil

import httpx
import pytest

from app import audio
from app.audio import prepare_audio
from app.stt import STT_PROMPT, parse_deepgram
from app.transcript import RawSegment, repair_segments
from tests.conftest import AUTH, LLM_URL, chat_reply, make_wav
from tests.test_auth_errors import assert_problem


def post_audio(client, data=None, name="a.wav"):
    data = make_wav(2.0) if data is None else data
    return client.post("/v1/audio/transcribe", headers=AUTH, files={"audio": (name, data)})


def stt_json(segments, duration=68.0):
    return json.dumps({"duration_s": duration, "segments": segments})


# ---- duration and timestamp repair -------------------------------------------------------


def test_duration_comes_from_audio_not_model(client, upstream):
    upstream.reply(
        chat_reply(stt_json([{"speaker": "A", "start": 0, "end": 1.5, "text": "Hi"}], 68.0))
    )
    body = post_audio(client, make_wav(2.0)).json()
    assert body["duration_s"] == 2.0


def test_segments_clamped_sorted_and_repaired(client, upstream):
    upstream.reply(
        chat_reply(
            stt_json(
                [
                    {"speaker": "B", "start": 1.0, "end": 1.9, "text": "second"},
                    {"speaker": "A", "start": -3.0, "end": 0.5, "text": "first"},
                    {"speaker": "A", "start": 1.9, "end": 1.2, "text": "reversed"},
                    {"speaker": "B", "start": 1.8, "end": 99.0, "text": "past the end"},
                    {"speaker": "A", "start": 0.2, "end": 0.4, "text": "   "},
                ]
            )
        )
    )
    segs = post_audio(client, make_wav(2.0)).json()["segments"]
    starts = [s["start"] for s in segs]
    assert starts == sorted(starts)
    for s in segs:
        assert 0 <= s["start"] <= s["end"] <= 2.0
    assert "   " not in [s["text"] for s in segs]
    assert len(segs) == 4  # blank-text segment dropped
    reversed_seg = next(s for s in segs if s["text"] == "reversed")
    assert (reversed_seg["start"], reversed_seg["end"]) == (1.2, 1.9)
    assert next(s for s in segs if s["text"] == "past the end")["end"] == 2.0
    assert next(s for s in segs if s["text"] == "first")["start"] == 0.0


def test_repair_segments_unit():
    raw = [
        RawSegment(speaker="A", start=float("nan"), end=1, text="bad"),
        RawSegment(speaker=0, start=5, end=6, text="x"),
        RawSegment(speaker=1, start=1, end=2, text="y"),
    ]
    out = repair_segments(raw, 10.0)
    assert [s.text for s in out] == ["y", "x"]
    assert [s.speaker for s in out] == ["A", "B"]  # labelled in speaking order


def test_numeric_speakers_map_to_letters():
    raw = [
        RawSegment(speaker=0, start=0, end=1, text="a"),
        RawSegment(speaker=1, start=1, end=2, text="b"),
        RawSegment(speaker=0, start=2, end=3, text="c"),
    ]
    assert [s.speaker for s in repair_segments(raw, 3.0)] == ["A", "B", "A"]


# ---- NanoGPT provider --------------------------------------------------------------------


def test_nanogpt_request_shape(client, upstream):
    upstream.reply(chat_reply(stt_json([{"speaker": "A", "start": 0, "end": 1, "text": "Hi"}])))
    wav = make_wav(1.0)
    post_audio(client, wav)
    req = upstream.requests[0]
    assert str(req.url) == LLM_URL
    assert req.headers["authorization"] == "Bearer nano-key"
    body = upstream.json(0)
    assert body["model"] == "xiaomi/mimo-v2.6-pro"
    assert body["reasoning_effort"] == "none"
    assert body["temperature"] == 0
    assert body["response_format"] == {"type": "json_object"}
    parts = body["messages"][0]["content"]
    assert parts[0] == {"type": "text", "text": STT_PROMPT}
    assert parts[1]["type"] == "input_audio"
    assert parts[1]["input_audio"]["format"] == "wav"
    assert base64.b64decode(parts[1]["input_audio"]["data"]) == wav


def test_nanogpt_strips_think_and_fences(client, upstream):
    seg = [{"speaker": "A", "start": 0, "end": 1, "text": "Hi"}]
    upstream.reply(chat_reply("<think>hmm {x}</think>\n```json\n" + stt_json(seg) + "\n```"))
    resp = post_audio(client)
    assert resp.status_code == 200
    assert resp.json()["segments"][0]["text"] == "Hi"


def test_nanogpt_json_retry_then_success(client, upstream):
    seg = [{"speaker": "A", "start": 0, "end": 1, "text": "Hi"}]
    upstream.reply(chat_reply("not json"), chat_reply(stt_json(seg)))
    assert post_audio(client).status_code == 200
    assert len(upstream.requests) == 2


def test_nanogpt_json_retries_exhausted(client, upstream):
    upstream.reply(chat_reply("nope"), chat_reply('{"segments": "wrong"}'))
    assert_problem(post_audio(client), 502, "transcription_failed")
    assert len(upstream.requests) == 2


def test_upstream_5xx_is_retried_with_backoff(make_client, upstream):
    client = make_client(upstream_retries=2)
    seg = [{"speaker": "A", "start": 0, "end": 1, "text": "Hi"}]
    upstream.reply(httpx.Response(503), httpx.Response(429), chat_reply(stt_json(seg)))
    assert post_audio(client).status_code == 200
    assert len(upstream.requests) == 3


def test_upstream_failure_after_retries_is_502(make_client, upstream):
    client = make_client(upstream_retries=1)
    upstream.reply(httpx.Response(500, text="secret upstream body"), httpx.Response(500))
    resp = post_audio(client)
    assert_problem(resp, 502, "upstream_error")
    assert "secret upstream body" not in resp.text
    assert len(upstream.requests) == 2


def test_upstream_client_error_not_retried(client, upstream):
    upstream.reply(httpx.Response(401))
    assert_problem(post_audio(client), 502, "upstream_error")
    assert len(upstream.requests) == 1


def test_upstream_timeout_is_504(make_client, upstream):
    client = make_client(upstream_retries=1)
    upstream.reply(httpx.ReadTimeout("t"), httpx.ConnectTimeout("t"))
    assert_problem(post_audio(client), 504, "upstream_timeout")


def test_upstream_network_error_retried_then_502(make_client, upstream):
    client = make_client(upstream_retries=1)
    upstream.reply(httpx.ConnectError("x"), httpx.ConnectError("x"))
    assert_problem(post_audio(client), 502, "upstream_error")
    assert len(upstream.requests) == 2


def test_missing_nanogpt_key_is_503(make_client):
    client = make_client(nanogpt_api_key="")
    # settings treat empty strings as unset, so the key is empty
    assert_problem(post_audio(client), 503, "upstream_not_configured")


# ---- Deepgram provider -------------------------------------------------------------------

DG_UTTERANCES = {
    "results": {
        "utterances": [
            {"speaker": 1, "start": 2.0, "end": 3.0, "transcript": "Nice to meet you"},
            {"speaker": 0, "start": 0.0, "end": 1.9, "transcript": "Hi, I'm Sam."},
        ]
    }
}


def test_deepgram_provider(make_client, upstream):
    client = make_client(stt_provider="deepgram")
    upstream.reply(httpx.Response(200, json=DG_UTTERANCES))
    wav = make_wav(4.0)
    body = post_audio(client, wav).json()
    req = upstream.requests[0]
    assert req.url.host == "dg.test"
    assert req.url.path == "/v1/listen"
    assert req.url.params["diarize"] == "true"
    assert req.url.params["utterances"] == "true"
    assert req.headers["authorization"] == "Token dg-key"
    assert req.headers["content-type"] == "audio/wav"
    assert req.content == wav
    assert body["duration_s"] == 4.0
    assert [(s["speaker"], s["text"]) for s in body["segments"]] == [
        ("A", "Hi, I'm Sam."),
        ("B", "Nice to meet you"),
    ]


def test_deepgram_words_fallback():
    payload = {
        "results": {
            "channels": [
                {
                    "alternatives": [
                        {
                            "words": [
                                {
                                    "word": "hi",
                                    "punctuated_word": "Hi",
                                    "start": 0,
                                    "end": 0.4,
                                    "speaker": 0,
                                },
                                {
                                    "word": "sam",
                                    "punctuated_word": "Sam.",
                                    "start": 0.4,
                                    "end": 0.9,
                                    "speaker": 0,
                                },
                                {
                                    "word": "hello",
                                    "punctuated_word": "Hello",
                                    "start": 1,
                                    "end": 1.4,
                                    "speaker": 1,
                                },
                            ]
                        }
                    ]
                }
            ]
        }
    }
    segs = parse_deepgram(payload)
    assert [(s.speaker, s.text, s.start, s.end) for s in segs] == [
        (0, "Hi Sam.", 0, 0.9),
        (1, "Hello", 1, 1.4),
    ]


def test_deepgram_retries_and_errors(make_client, upstream):
    client = make_client(stt_provider="deepgram", upstream_retries=1)
    upstream.reply(httpx.Response(502), httpx.Response(200, json=DG_UTTERANCES))
    assert post_audio(client).status_code == 200
    assert len(upstream.requests) == 2
    upstream.reply(httpx.Response(500), httpx.Response(500))
    assert_problem(post_audio(client), 502, "upstream_error")


def test_deepgram_bad_shape(make_client, upstream):
    client = make_client(stt_provider="deepgram")
    upstream.reply(httpx.Response(200, json={"unexpected": True}))
    assert_problem(post_audio(client), 502, "upstream_error")


def test_deepgram_missing_key(make_client):
    client = make_client(stt_provider="deepgram", deepgram_api_key="")
    assert_problem(post_audio(client), 503, "upstream_not_configured")


def test_deepgram_timeout(make_client, upstream):
    client = make_client(stt_provider="deepgram", upstream_retries=0)
    upstream.reply(httpx.ReadTimeout("t"))
    assert_problem(post_audio(client), 504, "upstream_timeout")


# ---- audio normalization -----------------------------------------------------------------


def test_missing_ffmpeg_rejects_non_wav_clearly(client, monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda name: None)
    resp = post_audio(client, b"\x00\x00\x00\x18ftypM4A fake", name="a.m4a")
    body = assert_problem(resp, 415, "unsupported_audio_format")
    assert "ffmpeg" in body["detail"]


def test_missing_ffmpeg_plain_wav_still_works(client, upstream, monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda name: None)
    seg = [{"speaker": "A", "start": 0, "end": 1, "text": "Hi"}]
    upstream.reply(chat_reply(stt_json(seg)))
    # not 16 kHz mono: passed through unchanged because ffmpeg is unavailable
    wav = make_wav(1.0, rate=8000, channels=2)
    resp = post_audio(client, wav)
    assert resp.status_code == 200
    assert resp.json()["duration_s"] == 1.0
    sent = upstream.json(0)["messages"][0]["content"][1]["input_audio"]["data"]
    assert base64.b64decode(sent) == wav


def test_16k_mono_wav_skips_ffmpeg(monkeypatch):
    called = []
    monkeypatch.setattr(shutil, "which", lambda name: "/usr/bin/ffmpeg")

    async def fake(*a, **k):
        called.append(1)

    monkeypatch.setattr(audio, "_convert_with_ffmpeg", fake)
    out = asyncio.run(prepare_audio(make_wav(1.5)))
    assert not called
    assert out.duration_s == 1.5


def test_other_formats_converted_when_ffmpeg_available(monkeypatch):
    seen = {}
    monkeypatch.setattr(shutil, "which", lambda name: "/usr/bin/ffmpeg")

    async def fake(ffmpeg, data, timeout_s):
        seen["data"] = data
        return make_wav(3.0)

    monkeypatch.setattr(audio, "_convert_with_ffmpeg", fake)
    out = asyncio.run(prepare_audio(b"fake-m4a-bytes"))
    assert seen["data"] == b"fake-m4a-bytes"
    assert out.duration_s == 3.0
    stereo = asyncio.run(prepare_audio(make_wav(1.0, rate=44100, channels=2)))
    assert stereo.duration_s == 3.0  # converted result used


def test_zero_length_wav_rejected(client, monkeypatch):
    monkeypatch.setattr(shutil, "which", lambda name: None)
    assert_problem(post_audio(client, make_wav(0.0)), 422, "invalid_audio")


class _FakeProc:
    def __init__(self, code):
        self.code = code

    async def wait(self):
        return self.code

    def kill(self):
        pass


def _fake_ffmpeg(monkeypatch, code, tmp_files):
    real_mkstemp = audio.tempfile.mkstemp

    def spy(*a, **k):
        fd, path = real_mkstemp(*a, **k)
        tmp_files.append(path)
        return fd, path

    monkeypatch.setattr(audio.tempfile, "mkstemp", spy)

    async def fake_exec(*args, **kwargs):
        if code == 0:
            pathlib.Path(args[-1]).write_bytes(make_wav(1.0))  # noqa: ASYNC240
        return _FakeProc(code)

    monkeypatch.setattr(audio.asyncio, "create_subprocess_exec", fake_exec)
    monkeypatch.setattr(shutil, "which", lambda name: "ffmpeg")


def test_ffmpeg_temp_files_deleted_on_success(monkeypatch):
    import os

    files: list[str] = []
    _fake_ffmpeg(monkeypatch, 0, files)
    out = asyncio.run(prepare_audio(b"not a wav"))
    assert out.duration_s == 1.0
    assert len(files) == 2
    assert not any(os.path.exists(p) for p in files)


def test_ffmpeg_failure_is_invalid_audio_and_cleans_up(client, monkeypatch):
    import os

    files: list[str] = []
    _fake_ffmpeg(monkeypatch, 1, files)
    resp = post_audio(client, b"garbage", name="x.m4a")
    assert_problem(resp, 422, "invalid_audio")
    assert len(files) == 2
    assert not any(os.path.exists(p) for p in files)


@pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg not installed")
def test_real_ffmpeg_resamples_to_16k_mono():
    import io
    import wave

    out = asyncio.run(prepare_audio(make_wav(1.0, rate=44100, channels=2)))
    with wave.open(io.BytesIO(out.wav)) as w:
        assert (w.getframerate(), w.getnchannels()) == (16000, 1)
    assert out.duration_s == pytest.approx(1.0, abs=0.05)
