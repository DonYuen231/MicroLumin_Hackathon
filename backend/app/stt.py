"""Speech-to-text providers behind one interface. Duration is never taken from a provider."""

import base64
from typing import Any, Protocol

import httpx
from pydantic import ValidationError

from app.audio import PreparedAudio
from app.config import Settings
from app.errors import ProblemError
from app.http import Sleep, post_with_retry
from app.llm import ChatClient, ModelOutputError, extract_json
from app.transcript import RawSegment, RawTranscript, Transcript, repair_segments

STT_PROMPT = (
    "Transcribe this two-person conversation verbatim. Separate speakers by voice. "
    "Label the first speaker A and the other B. Start a new segment at every speaker change. "
    "Give each segment's start and end time in seconds. "
    'Return only JSON: {"duration_s": number, "segments": [{"speaker": "A", "start": 0.0, '
    '"end": 1.0, "text": "..."}]}'
)


class Transcriber(Protocol):
    async def transcribe(self, audio: PreparedAudio) -> list[RawSegment]: ...


class NanoGPTTranscriber:
    def __init__(self, settings: Settings, chat: ChatClient) -> None:
        self._settings = settings
        self._chat = chat

    async def transcribe(self, audio: PreparedAudio) -> list[RawSegment]:
        b64 = base64.b64encode(audio.wav).decode("ascii")
        body: dict[str, Any] = {
            "model": self._settings.stt_model,
            "reasoning_effort": "none",
            "temperature": 0,
            "response_format": {"type": "json_object"},
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": STT_PROMPT},
                        {"type": "input_audio", "input_audio": {"data": b64, "format": "wav"}},
                    ],
                }
            ],
        }
        attempts = self._settings.stt_json_retries + 1
        for _ in range(attempts):
            text = await self._chat.complete(body, timeout_s=self._settings.stt_timeout_s)
            try:
                return RawTranscript.model_validate(extract_json(text)).segments
            except (ModelOutputError, ValidationError):
                continue
        raise ProblemError(
            502, "transcription_failed", "The transcription model did not return valid JSON."
        )


class DeepgramTranscriber:
    def __init__(
        self, settings: Settings, client: httpx.AsyncClient, sleep: Sleep | None = None
    ) -> None:
        self._settings = settings
        self._client = client
        self._sleep = sleep

    async def transcribe(self, audio: PreparedAudio) -> list[RawSegment]:
        key = self._settings.deepgram_api_key.get_secret_value()
        if not key:
            raise ProblemError(
                503, "upstream_not_configured", "DEEPGRAM_API_KEY is not configured on the server."
            )
        extra = {"sleep": self._sleep} if self._sleep else {}
        resp = await post_with_retry(
            self._client,
            self._settings.deepgram_base_url.rstrip("/") + "/v1/listen",
            service="Deepgram",
            retries=self._settings.upstream_retries,
            backoff_s=self._settings.upstream_backoff_s,
            timeout_s=self._settings.stt_timeout_s,
            params={
                "model": self._settings.deepgram_model,
                "diarize": "true",
                "utterances": "true",
                "punctuate": "true",
                "smart_format": "true",
            },
            headers={"Authorization": f"Token {key}", "Content-Type": "audio/wav"},
            content=audio.wav,
            **extra,
        )
        try:
            return parse_deepgram(resp.json())
        except (ValueError, KeyError, IndexError, TypeError, ValidationError):
            raise ProblemError(
                502, "upstream_error", "Deepgram returned an unexpected response shape."
            ) from None


def parse_deepgram(payload: dict[str, Any]) -> list[RawSegment]:
    results = payload["results"]
    utterances = results.get("utterances")
    if utterances:
        return [
            RawSegment(
                speaker=u.get("speaker", 0),
                start=u["start"],
                end=u["end"],
                text=u["transcript"],
            )
            for u in utterances
        ]
    # Fall back to grouping consecutive diarized words by speaker.
    words = results["channels"][0]["alternatives"][0].get("words", [])
    groups: list[dict[str, Any]] = []
    for w in words:
        speaker = w.get("speaker", 0)
        text = w.get("punctuated_word") or w["word"]
        if groups and groups[-1]["speaker"] == speaker:
            groups[-1]["end"] = w["end"]
            groups[-1]["words"].append(text)
        else:
            groups.append(
                {"speaker": speaker, "start": w["start"], "end": w["end"], "words": [text]}
            )
    return [
        RawSegment(speaker=g["speaker"], start=g["start"], end=g["end"], text=" ".join(g["words"]))
        for g in groups
    ]


def build_transcriber(
    settings: Settings, client: httpx.AsyncClient, chat: ChatClient, sleep: Sleep | None = None
) -> Transcriber:
    if settings.stt_provider == "deepgram":
        return DeepgramTranscriber(settings, client, sleep)
    return NanoGPTTranscriber(settings, chat)


async def transcribe(transcriber: Transcriber, audio: PreparedAudio) -> Transcript:
    raw = await transcriber.transcribe(audio)
    return Transcript(duration_s=audio.duration_s, segments=repair_segments(raw, audio.duration_s))
