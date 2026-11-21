"""NanoGPT chat-completions client (OpenAI-compatible) and model-JSON cleanup."""

import json
import re
from typing import Any

import httpx

from app.config import Settings
from app.errors import ProblemError
from app.http import Sleep, post_with_retry

_THINK_BLOCK = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)
_FENCE = re.compile(r"^```[a-zA-Z0-9_-]*\s*\n?(.*?)\n?```\s*$", re.DOTALL)


class ModelOutputError(ValueError):
    """The model reply was not usable JSON."""


def extract_json(text: str) -> Any:
    """Strip <think> blocks and markdown fences, then parse JSON."""
    cleaned = _THINK_BLOCK.sub("", text)
    if "</think>" in cleaned.lower():  # opening tag was cut off
        cleaned = re.split(r"</think>", cleaned, flags=re.IGNORECASE)[-1]
    cleaned = cleaned.strip()
    fence = _FENCE.match(cleaned)
    if fence:
        cleaned = fence.group(1).strip()
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        start, end = cleaned.find("{"), cleaned.rfind("}")
        if start != -1 and end > start:
            try:
                return json.loads(cleaned[start : end + 1])
            except json.JSONDecodeError:
                pass
        raise ModelOutputError("Reply was not valid JSON.") from None


class ChatClient:
    def __init__(
        self, settings: Settings, client: httpx.AsyncClient, sleep: Sleep | None = None
    ) -> None:
        self._settings = settings
        self._client = client
        self._sleep = sleep

    async def complete(self, body: dict[str, Any], *, timeout_s: float) -> str:
        """POST {base}/chat/completions and return the message text."""
        key = self._settings.nanogpt_api_key.get_secret_value()
        if not key:
            raise ProblemError(
                503, "upstream_not_configured", "NANOGPT_API_KEY is not configured on the server."
            )
        url = self._settings.nanogpt_base_url.rstrip("/") + "/chat/completions"
        extra = {"sleep": self._sleep} if self._sleep else {}
        resp = await post_with_retry(
            self._client,
            url,
            service="NanoGPT",
            retries=self._settings.upstream_retries,
            backoff_s=self._settings.upstream_backoff_s,
            timeout_s=timeout_s,
            headers={"Authorization": f"Bearer {key}"},
            json=body,
            **extra,
        )
        try:
            content = resp.json()["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError):
            raise ProblemError(
                502, "upstream_error", "NanoGPT returned an unexpected response shape."
            ) from None
        if isinstance(content, list):  # content parts
            content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
        if not isinstance(content, str):
            raise ProblemError(502, "upstream_error", "NanoGPT returned no message text.")
        return content
