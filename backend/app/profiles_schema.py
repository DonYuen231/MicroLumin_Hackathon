"""Profile models (README section 8) and request models (section 7.3)."""

from datetime import UTC, datetime
from typing import Any

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    field_serializer,
)

from app.transcript import Segment


def _iso_z(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


class MetAt(BaseModel):
    time: AwareDatetime
    place: str = ""

    @field_serializer("time")
    def _ser_time(self, value: datetime) -> str:
        return _iso_z(value)


class Encounter(BaseModel):
    model_config = ConfigDict(extra="ignore")

    time: AwareDatetime
    place: str = ""
    notes: str = ""

    @field_serializer("time")
    def _ser_time(self, value: datetime) -> str:
        return _iso_z(value)


class Profile(BaseModel):
    """A stored profile. Lenient defaults so a phone-edited or partial profile still loads."""

    model_config = ConfigDict(extra="ignore")

    name: str = "Unknown"
    headline: str = ""
    summary: str = ""
    encounters: list[Encounter] = []
    topics: list[str] = []
    follow_ups: list[str] = []
    avoid: list[str] = []
    since_you_met: list[str] = []
    shared_links: list[str] = []
    spoken: list[str] = []


class LLMEncounter(BaseModel):
    model_config = ConfigDict(extra="ignore")

    notes: str = ""


class LLMProfile(BaseModel):
    """What the model must return. Required keys are strict; the rest the server controls."""

    model_config = ConfigDict(extra="ignore")

    name: str = Field(min_length=1)
    headline: str
    summary: str
    topics: list[str]
    follow_ups: list[str]
    avoid: list[str]
    spoken: list[str]
    encounters: list[LLMEncounter] = []
    since_you_met: list[Any] = []
    shared_links: list[Any] = []


class TranscriptIn(BaseModel):
    segments: list[Segment] = Field(min_length=1)


class BuildRequest(BaseModel):
    transcript: TranscriptIn
    wearer_name: str = Field(min_length=1)
    existing_profile: Profile | None = None
    met_at: MetAt
    shared_links: list[str] = []


class RefreshRequest(BaseModel):
    person_id: str
    name: str
    shared_links: list[str] = []
    profile: dict[str, Any] | None = None


class RefreshResponse(BaseModel):
    since_you_met: list[str]
