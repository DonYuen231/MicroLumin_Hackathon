"""Streaming multipart reader: in memory only, bounded by the body limit, never spooled to disk."""

import io

from fastapi import Request
from python_multipart.multipart import File, FormParser, parse_options_header

from app.errors import ProblemError

_IN_MEMORY = 1 << 40  # never spill a part to a temp file


async def read_upload(request: Request, field: str, max_bytes: int) -> bytes:
    content_type = request.headers.get("content-type", "")
    ctype, params = parse_options_header(content_type)
    if ctype.lower() != b"multipart/form-data" or b"boundary" not in params:
        raise ProblemError(415, "unsupported_media_type", "Expected multipart/form-data.")

    found: dict[str, bytes] = {}

    def on_file(f: File) -> None:
        name = (f.field_name or b"").decode("utf-8", "replace")
        if name == field and field not in found:
            buf = f.file_object
            assert isinstance(buf, io.BytesIO)
            found[field] = buf.getvalue()
        f.close()

    parser = FormParser(
        "multipart/form-data",
        boundary=params[b"boundary"],
        on_field=None,
        on_file=on_file,
        config={"MAX_MEMORY_FILE_SIZE": _IN_MEMORY},
    )
    try:
        async for chunk in request.stream():
            parser.write(chunk)
        parser.finalize()
    except ProblemError:
        raise
    except Exception:
        raise ProblemError(422, "validation_error", "Malformed multipart body.") from None
    finally:
        parser.close()

    data = found.get(field)
    if data is None:
        raise ProblemError(422, "validation_error", f"Multipart file field '{field}' is required.")
    if len(data) == 0:
        raise ProblemError(422, "validation_error", f"Multipart field '{field}' is empty.")
    if len(data) > max_bytes:
        raise ProblemError(
            413, "payload_too_large", f"'{field}' exceeds the {max_bytes} byte limit."
        )
    return data
