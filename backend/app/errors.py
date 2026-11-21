"""RFC 9457 problem details for every failure."""

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

log = logging.getLogger("recall")

PROBLEM_JSON = "application/problem+json"

_STATUS_TITLES = {
    400: "bad_request",
    401: "unauthorized",
    404: "not_found",
    405: "method_not_allowed",
    413: "payload_too_large",
    415: "unsupported_media_type",
    422: "validation_error",
}


class ProblemError(StarletteHTTPException):
    """Raise this anywhere; it becomes a problem-details response."""

    def __init__(
        self,
        status: int,
        title: str,
        detail: str,
        headers: dict[str, str] | None = None,
        extra: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(status_code=status, detail=detail, headers=headers)
        self.title = title
        self.extra = extra or {}


def problem_response(
    request: Request,
    status: int,
    title: str,
    detail: str,
    headers: dict[str, str] | None = None,
    extra: dict[str, Any] | None = None,
) -> JSONResponse:
    body: dict[str, Any] = {
        "type": f"urn:recall:problem:{title}",
        "title": title,
        "status": status,
        "detail": detail,
    }
    request_id = request.scope.get("request_id")
    if request_id:
        body["request_id"] = request_id
    if extra:
        body.update(extra)
    return JSONResponse(body, status_code=status, media_type=PROBLEM_JSON, headers=headers)


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ProblemError)
    async def _problem(request: Request, exc: ProblemError) -> JSONResponse:
        return problem_response(
            request, exc.status_code, exc.title, str(exc.detail), exc.headers, exc.extra
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        title = _STATUS_TITLES.get(exc.status_code, f"http_{exc.status_code}")
        return problem_response(request, exc.status_code, title, str(exc.detail), exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        # Locations and messages only: never echo the submitted values back.
        errors = [
            {"loc": [str(p) for p in e.get("loc", ())], "msg": str(e.get("msg", ""))}
            for e in exc.errors()
        ]
        return problem_response(
            request,
            422,
            "validation_error",
            "Request validation failed.",
            extra={"errors": errors},
        )

    @app.exception_handler(Exception)
    async def _unhandled(request: Request, exc: Exception) -> JSONResponse:
        log.error(
            "unhandled error request_id=%s type=%s",
            request.scope.get("request_id"),
            type(exc).__name__,
        )
        return problem_response(request, 500, "internal_error", "Internal server error.")
