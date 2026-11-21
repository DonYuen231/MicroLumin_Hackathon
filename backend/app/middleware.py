"""Pure ASGI middleware: request IDs and timing logs, and streaming body-size limits."""

import logging
import time
import uuid

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.errors import ProblemError

log = logging.getLogger("recall")

# Multipart framing overhead allowed on top of the file limit.
MULTIPART_OVERHEAD = 64 * 1024


class RequestContextMiddleware:
    """Assigns a request ID and logs method, path, status and timing. Never logs bodies."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        request_id = uuid.uuid4().hex[:12]
        scope["request_id"] = request_id
        start = time.perf_counter()
        status = 0

        async def send_wrapper(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                headers = list(message.get("headers", []))
                headers.append((b"x-request-id", request_id.encode()))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            log.info(
                "request_id=%s %s %s status=%s ms=%.0f",
                request_id,
                scope["method"],
                scope["path"],
                status,
                (time.perf_counter() - start) * 1000,
            )


class BodyLimitMiddleware:
    """Counts the bytes actually received and aborts with a 413 problem once over the limit.

    Nothing is buffered here, and the declared Content-Length is not trusted.
    """

    def __init__(self, app: ASGIApp, limits: dict[str, int], default: int) -> None:
        self.app = app
        self.limits = limits
        self.default = default

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        limit = self.limits.get(scope["path"], self.default)
        declared: int | None = None
        for name, value in scope["headers"]:
            if name == b"content-length":
                try:
                    declared = int(value)
                except ValueError:
                    declared = None
        received = 0

        def too_large() -> ProblemError:
            return ProblemError(
                413, "payload_too_large", f"Request body exceeds the {limit} byte limit."
            )

        async def limited_receive() -> Message:
            nonlocal received
            if declared is not None and declared > limit:
                raise too_large()
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise too_large()
            return message

        await self.app(scope, limited_receive, send)
