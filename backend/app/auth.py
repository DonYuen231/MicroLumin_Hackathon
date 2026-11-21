import hashlib
import hmac

from fastapi import Request

from app.errors import ProblemError

_CHALLENGE = {"WWW-Authenticate": "Bearer"}


def _digest(value: str) -> bytes:
    # Hash first so the comparison time does not depend on the token length either.
    return hashlib.sha256(value.encode("utf-8")).digest()


def require_auth(request: Request) -> None:
    expected: str = request.app.state.settings.app_token.get_secret_value()
    header = request.headers.get("authorization", "")
    scheme, _, supplied = header.partition(" ")
    ok = scheme.lower() == "bearer" and bool(supplied.strip())
    # Always run the comparison so timing does not reveal which check failed.
    match = hmac.compare_digest(_digest(supplied.strip()), _digest(expected))
    if not (ok and match):
        raise ProblemError(
            401,
            "unauthorized",
            "Missing or invalid bearer token.",
            headers=_CHALLENGE,
        )
