"""Profile models (README section 8) and request models (section 7.3)."""

from datetime import UTC, datetime
from typing import Any, Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    field_serializer,
    field_validator,
)

from app.transcript import Segment

MAX_FOLLOW_UP_HOURS = 720  # 30 days
FollowUpChannel = Literal["message", "email", "none"]


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


class FollowUp(BaseModel):
    """A stored follow-up. One saved before timing existed has no due_in_hours."""

    model_config = ConfigDict(extra="ignore")

    text: str = Field(min_length=1)
    reason: str = ""
    due_in_hours: float | None = Field(default=None, gt=0, allow_inf_nan=False)
    channel: FollowUpChannel = "none"
    draft: str = ""


class Profile(BaseModel):
    """A stored profile. Lenient defaults so a phone-edited or partial profile still loads."""

    model_config = ConfigDict(extra="ignore")

    name: str = "Unknown"
    headline: str = ""
    summary: str = ""
    encounters: list[Encounter] = []
    topics: list[str] = []
    follow_ups: list[FollowUp] = []
    avoid: list[str] = []
    since_you_met: list[str] = []
    shared_links: list[str] = []
    spoken: list[str] = []

    @field_validator("follow_ups", mode="before")
    @classmethod
    def _accept_plain_strings(cls, value: Any) -> Any:
        """Profiles saved before this change hold follow-ups as plain strings."""
        if not isinstance(value, list):
            return value
        items: list[Any] = []
        for item in value:
            if not isinstance(item, str):
                items.append(item)
            elif item.strip():
                items.append({"text": item.strip()})
        return items


class LLMEncounter(BaseModel):
    model_config = ConfigDict(extra="ignore")

    notes: str = ""


class LLMFollowUp(BaseModel):
    """What the model must return for each follow-up. Every key is required."""

    model_config = ConfigDict(extra="ignore")

    text: str = Field(min_length=1)
    reason: str
    due_in_hours: float = Field(gt=0, le=MAX_FOLLOW_UP_HOURS, allow_inf_nan=False)
    channel: FollowUpChannel
    draft: str


class LLMProfile(BaseModel):
    """What the model must return. Required keys are strict; the rest the server controls."""

    model_config = ConfigDict(extra="ignore")

    name: str = Field(min_length=1)
    headline: str
    summary: str
    topics: list[str]
    follow_ups: list[LLMFollowUp]
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
