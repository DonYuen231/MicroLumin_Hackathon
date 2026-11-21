"""Outside calls: timeouts, retries with exponential backoff. Never logs bodies."""

import asyncio
import logging
from collections.abc import Awaitable, Callable
from typing import Any

import httpx

from app.errors import ProblemError

log = logging.getLogger("recall")

_RETRY_STATUS = {408, 425, 429, 500, 502, 503, 504}

Sleep = Callable[[float], Awaitable[None]]


async def post_with_retry(
    client: httpx.AsyncClient,
    url: str,
    *,
    service: str,
    retries: int,
    backoff_s: float,
    timeout_s: float,
    sleep: Sleep = asyncio.sleep,
    **kwargs: Any,
) -> httpx.Response:
    """POST with retries on network errors, timeouts, 408/425/429 and 5xx. Raises ProblemError."""
    last_problem: ProblemError | None = None
    for attempt in range(retries + 1):
        try:
            resp = await client.post(url, timeout=timeout_s, **kwargs)
        except httpx.TimeoutException:
            last_problem = ProblemError(504, "upstream_timeout", f"{service} timed out.")
        except httpx.HTTPError:
            last_problem = ProblemError(502, "upstream_error", f"{service} is unreachable.")
        else:
            if resp.status_code < 400:
                return resp
            if resp.status_code not in _RETRY_STATUS:
                raise ProblemError(
                    502, "upstream_error", f"{service} rejected the request ({resp.status_code})."
                )
            last_problem = ProblemError(
                502, "upstream_error", f"{service} failed ({resp.status_code})."
            )
        if attempt < retries:
            log.warning("retrying %s call attempt=%d", service, attempt + 1)
            await sleep(backoff_s * (2**attempt))
    assert last_problem is not None
    raise last_problem
