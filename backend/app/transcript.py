"""Transcript models and repair of model-reported segments."""

import math
import string

from pydantic import BaseModel, ConfigDict


class Segment(BaseModel):
    speaker: str
    start: float
    end: float
    text: str


class Transcript(BaseModel):
    duration_s: float
    segments: list[Segment]


class RawSegment(BaseModel):
    """A segment as reported by a provider: loose types, repaired later."""

    model_config = ConfigDict(extra="ignore")

    speaker: str | int | float
    start: float
    end: float
    text: str


class RawTranscript(BaseModel):
    model_config = ConfigDict(extra="ignore")

    segments: list[RawSegment]


def _speaker_labels(raw: list[RawSegment]) -> dict[str, str]:
    """Letters A, B, C... A single letter is kept; other labels map by first appearance."""
    mapping: dict[str, str] = {}
    used: set[str] = set()
    for seg in raw:
        label = str(seg.speaker).strip()
        if len(label) == 1 and label.upper() in string.ascii_uppercase:
            used.add(label.upper())
    letters = (c for c in string.ascii_uppercase if c not in used)
    for seg in raw:
        label = str(seg.speaker).strip()
        if label in mapping:
            continue
        if len(label) == 1 and label.upper() in string.ascii_uppercase:
            mapping[label] = label.upper()
        else:
            mapping[label] = next(letters, "Z")
    return mapping


def repair_segments(raw: list[RawSegment], duration_s: float) -> list[Segment]:
    """Drop unusable segments, clamp times to [0, duration], fix end < start, sort by start."""
    usable = [s for s in raw if s.text.strip() and math.isfinite(s.start) and math.isfinite(s.end)]
    usable.sort(key=lambda s: s.start)  # label speakers in the order they actually speak
    labels = _speaker_labels(usable)
    out: list[Segment] = []
    for seg in usable:
        text = seg.text.strip()
        start = min(max(seg.start, 0.0), duration_s)
        end = min(max(seg.end, 0.0), duration_s)
        if end < start:
            start, end = end, start
        out.append(
            Segment(
                speaker=labels[str(seg.speaker).strip()],
                start=round(start, 2),
                end=round(end, 2),
                text=text,
            )
        )
    out.sort(key=lambda s: (s.start, s.end))
    return out
