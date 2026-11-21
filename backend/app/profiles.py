"""Profile building: LLM call, section 8 enforcement, server-controlled fields, merge, refresh."""

import json
import logging
import re
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from app.config import Settings
from app.errors import ProblemError
from app.llm import ChatClient, ModelOutputError, extract_json
from app.profiles_schema import BuildRequest, Encounter, FollowUp, LLMProfile, Profile
from app.prompts import SYSTEM, build_user_message, retry_message

log = logging.getLogger("recall")

MAX_ITEM_CHARS = 60
MAX_SPOKEN_ITEMS = 4
MAX_NOTES_CHARS = 300
MAX_REASON_CHARS = 120
MAX_DRAFT_CHARS = 300
LINK = re.compile(r"https?://|www\.", re.IGNORECASE)


def _norm(text: str) -> str:
    return re.sub(r"[^a-z0-9 ]+", "", text.lower()).strip()


def check_rules(p: LLMProfile) -> list[str]:
    """Section 8 rules the model must satisfy. Returns human-readable violations."""
    errors: list[str] = []
    fields: dict[str, list[str]] = {
        "headline": [p.headline],
        "topics": p.topics,
        "follow_ups": [f.text for f in p.follow_ups],
        "avoid": p.avoid,
        "spoken": p.spoken,
    }
    for field, items in fields.items():
        for item in items:
            if len(item) > MAX_ITEM_CHARS:
                errors.append(
                    f"{field} item is {len(item)} characters, "
                    f'the limit is {MAX_ITEM_CHARS}: "{item}"'
                )
    if not p.spoken:
        errors.append("spoken must contain at least one item, starting with the person's name.")
    if len(p.spoken) > MAX_SPOKEN_ITEMS:
        errors.append(f"spoken has {len(p.spoken)} items, the limit is {MAX_SPOKEN_ITEMS}.")
    if p.spoken:
        first = p.spoken[0].lower()
        name = p.name.strip().lower()
        first_name = name.split()[0] if name else ""
        if name not in first and first_name not in first:
            errors.append(f'spoken[0] must contain the name "{p.name}".')
    avoid_norm = [a for a in (_norm(x) for x in p.avoid) if a]
    for item in p.spoken:
        n = _norm(item)
        # An avoid topic inside a spoken item, or a spoken item that is a chunk of one.
        overlaps = n and any(a in n or (len(n) >= 12 and n in a) for a in avoid_norm)
        if re.match(r"\s*avoid\b", item, re.IGNORECASE) or overlaps:
            errors.append(f'spoken must not include anything from avoid: "{item}"')
    for follow_up in p.follow_ups:
        errors.extend(_follow_up_errors(follow_up, avoid_norm))
    return errors


def _follow_up_errors(f: Any, avoid_norm: list[str]) -> list[str]:
    """A draft is text the wearer may send, so it is held to stricter rules than other fields."""
    errors: list[str] = []
    label = f'follow_ups item "{f.text}"'
    if len(f.reason) > MAX_REASON_CHARS:
        errors.append(f"{label}: reason is over {MAX_REASON_CHARS} characters.")
    if len(f.draft) > MAX_DRAFT_CHARS:
        errors.append(f"{label}: draft is over {MAX_DRAFT_CHARS} characters.")
    if f.channel == "none" and f.draft.strip():
        errors.append(f'{label}: draft must be empty when channel is "none".')
    if f.channel != "none" and not f.draft.strip():
        errors.append(f"{label}: draft is required when channel is {f.channel}.")
    if LINK.search(f.draft):
        errors.append(f'{label}: draft must not contain a link. Write "[add link]" instead.')
    draft_norm = _norm(f.draft)
    if any(a in draft_norm for a in avoid_norm):
        errors.append(f"{label}: draft must not mention anything from avoid.")
    return errors


def _pydantic_errors(exc: ValidationError) -> list[str]:
    return [f"{'.'.join(str(x) for x in e['loc'])}: {e['msg']}" for e in exc.errors()]


def _unique(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            out.append(item)
    return out


def assemble(llm: LLMProfile, req: BuildRequest) -> Profile:
    """Apply the server-controlled fields on top of the validated model output."""
    existing = req.existing_profile
    notes = llm.encounters[-1].notes.strip()[:MAX_NOTES_CHARS] if llm.encounters else ""
    new_encounter = Encounter(time=req.met_at.time, place=req.met_at.place, notes=notes)
    encounters = list(existing.encounters) if existing else []
    for i, old in enumerate(encounters):
        if old.time == new_encounter.time and old.place == new_encounter.place:
            encounters[i] = new_encounter  # resubmitting the same meeting must not duplicate it
            break
    else:
        encounters.append(new_encounter)
    return Profile(
        name=llm.name.strip(),
        headline=llm.headline,
        summary=llm.summary,
        encounters=encounters,
        topics=llm.topics,
        follow_ups=[FollowUp.model_validate(f.model_dump()) for f in llm.follow_ups],
        avoid=llm.avoid,
        # since_you_met belongs to refresh only: whatever the model said is discarded.
        since_you_met=list(existing.since_you_met) if existing else [],
        shared_links=_unique((existing.shared_links if existing else []) + req.shared_links),
        spoken=llm.spoken,
    )


async def build_profile(settings: Settings, chat: ChatClient, req: BuildRequest) -> Profile:
    user = build_user_message(
        transcript_segments=[s.model_dump() for s in req.transcript.segments],
        wearer_name=req.wearer_name,
        met_at=req.met_at.model_dump(mode="json"),
        existing_profile=req.existing_profile.model_dump(mode="json")
        if req.existing_profile
        else None,
    )
    messages: list[dict[str, str]] = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": user},
    ]
    for attempt in range(settings.llm_json_retries + 1):
        body: dict[str, Any] = {
            "model": settings.llm_model,
            "reasoning_effort": "low",
            "temperature": 0.1,
            "response_format": {"type": "json_object"},
            "messages": messages,
        }
        text = await chat.complete(body, timeout_s=settings.llm_timeout_s)
        try:
            llm = LLMProfile.model_validate(extract_json(text))
            errors = check_rules(llm)
        except ModelOutputError:
            errors = ["The reply was not a single valid JSON object."]
        except ValidationError as exc:
            errors = _pydantic_errors(exc)
        if not errors:
            return assemble(llm, req)
        log.warning("profile rejected attempt=%d problems=%d", attempt + 1, len(errors))
        messages = [
            *messages,
            {"role": "assistant", "content": text},
            {"role": "user", "content": retry_message(errors)},
        ]
    raise ProblemError(
        502,
        "profile_generation_failed",
        "The model did not produce a valid profile after retries.",
    )


def load_refresh_fixture(path: Path) -> dict[str, list[str]]:
    """Canned 'since you met' updates keyed by lower-cased name."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        log.warning("refresh fixture missing or unreadable")
        return {}
    return {
        str(k).strip().lower(): [str(i) for i in v] for k, v in raw.items() if isinstance(v, list)
    }
