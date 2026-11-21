"""Audio normalization to 16 kHz mono WAV, and duration measured from the audio itself."""

import asyncio
import contextlib
import io
import os
import shutil
import tempfile
import wave
from dataclasses import dataclass

from app.errors import ProblemError

TARGET_RATE = 16000


@dataclass(frozen=True)
class PreparedAudio:
    wav: bytes
    duration_s: float


def _read_wav(data: bytes) -> tuple[int, int, int, float] | None:
    """Return (channels, rate, sample_width, duration_s) for plain PCM WAV, else None."""
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        return None
    try:
        with wave.open(io.BytesIO(data), "rb") as w:
            rate = w.getframerate()
            if rate <= 0:
                return None
            return w.getnchannels(), rate, w.getsampwidth(), w.getnframes() / rate
    except (wave.Error, EOFError):
        return None


def _read_file(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read()


async def _convert_with_ffmpeg(ffmpeg: str, data: bytes, timeout_s: float) -> bytes:
    """Convert to 16 kHz mono 16-bit WAV via temp files, which are always deleted."""
    in_fd, in_path = tempfile.mkstemp(suffix=".in")
    out_fd, out_path = tempfile.mkstemp(suffix=".wav")
    os.close(out_fd)
    try:
        with os.fdopen(in_fd, "wb") as f:
            f.write(data)
        proc = await asyncio.create_subprocess_exec(
            ffmpeg,
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-i",
            in_path,
            "-vn",
            "-ac",
            "1",
            "-ar",
            str(TARGET_RATE),
            "-c:a",
            "pcm_s16le",
            "-f",
            "wav",
            out_path,
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            code = await asyncio.wait_for(proc.wait(), timeout_s)
        except TimeoutError:
            proc.kill()
            await proc.wait()
            raise ProblemError(
                504, "audio_conversion_timeout", "Audio conversion timed out."
            ) from None
        if code != 0:
            raise ProblemError(
                422, "invalid_audio", "The audio could not be decoded. Is it a valid audio file?"
            )
        return await asyncio.to_thread(_read_file, out_path)
    finally:
        for path in (in_path, out_path):
            with contextlib.suppress(OSError):
                os.remove(path)


async def prepare_audio(data: bytes, *, ffmpeg_timeout_s: float = 60.0) -> PreparedAudio:
    """Return 16 kHz mono WAV bytes plus the duration computed from the audio."""
    info = _read_wav(data)
    if info is not None:
        channels, rate, width, duration = info
        if channels == 1 and rate == TARGET_RATE and width == 2:
            return _checked(data, duration)

    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        if info is not None:
            # Plain WAV still works without ffmpeg; it just is not resampled.
            return _checked(data, info[3])
        raise ProblemError(
            415,
            "unsupported_audio_format",
            "Only PCM WAV is accepted because ffmpeg is not installed on the server.",
        )
    converted = await _convert_with_ffmpeg(ffmpeg, data, ffmpeg_timeout_s)
    out = _read_wav(converted)
    if out is None:
        raise ProblemError(422, "invalid_audio", "The audio could not be decoded.")
    return _checked(converted, out[3])


def _checked(wav: bytes, duration: float) -> PreparedAudio:
    if duration <= 0:
        raise ProblemError(422, "invalid_audio", "The audio contains no samples.")
    return PreparedAudio(wav=wav, duration_s=round(duration, 2))
