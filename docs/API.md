# API Reference

Recall has three services that talk over HTTP. All JSON uses `snake_case`, and all times are ISO 8601 in UTC.

| Service | Base URL | Called by | Auth |
| --- | --- | --- | --- |
| Backend | Your tunnel URL, or `http://localhost:8000` | Phone | Bearer token |
| Phone hub | `http://<phone>:8080` | Glasses | Trusted address |
| Glasses | `http://<glasses>:8081` | Phone | Trusted address |

## Backend

Every `/v1` request needs this header:

```http
Authorization: Bearer <APP_TOKEN>
```

Errors use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details with the `application/problem+json` content type:

```json
{
  "type": "urn:recall:problem:no_face",
  "title": "no_face",
  "status": 422,
  "detail": "No face was found in the image.",
  "request_id": "..."
}
```

Limits are 2 MB per image, 50 MB per audio file, and 1 MB per JSON body.

### `GET /healthz`

Needs no auth. Returns `{"ok": true}`.

### `POST /v1/faces/embed`

Multipart form with an `image` field. Returns one selected face with a 512-dimension, L2-normalized vector.

```json
{
  "faces": [
    {"bbox": [120.5, 80.2, 310.1, 330.7], "det_score": 0.98, "embedding": [0.012, -0.034, "..."]}
  ]
}
```

| Status | `title` | Meaning |
| --- | --- | --- |
| 422 | `no_face` | No face scored at least 0.5 |
| 422 | `multiple_faces` | No central face was at least 1.5 times larger than the next |
| 422 | `invalid_image` | The image could not be decoded |
| 503 | `face_engine_unavailable` | The face model isn't installed |

### `POST /v1/audio/transcribe`

Multipart form with an `audio` field. Audio is converted to 16 kHz mono WAV with ffmpeg when needed. Duration is measured from the audio, and speakers are labeled in speaking order.

```json
{
  "duration_s": 184.2,
  "segments": [
    {"speaker": "A", "start": 0.0, "end": 4.1, "text": "Hi, I'm Sam."},
    {"speaker": "B", "start": 4.3, "end": 6.0, "text": "Nice to meet you, I'm Alex."}
  ]
}
```

### `POST /v1/profiles/build`

Builds a profile, or merges a conversation into `existing_profile`.

```json
{
  "transcript": {"segments": [{"speaker": "A", "start": 0.0, "end": 4.1, "text": "Hi, I'm Sam."}]},
  "wearer_name": "Alex",
  "existing_profile": null,
  "met_at": {"time": "2026-11-21T15:00:00Z", "place": "Worn Hackathon"},
  "shared_links": []
}
```

`met_at.time` must include a time zone. The response is a [profile](#profile-format). If the model can't produce a valid profile after 2 retries, the endpoint returns 502 `profile_generation_failed`.

### `POST /v1/profiles/refresh`

**Simulated.** Returns canned updates from `backend/fixtures/refresh.json`, matched by name.

```json
{"person_id": "p_7f3a", "name": "Sam Rivera", "shared_links": [], "profile": {}}
```

```json
{"since_you_met": ["Started a new role at Helix Robotics"]}
```

## Profile format

```json
{
  "name": "Sam Rivera",
  "headline": "ML engineer at Acme",
  "summary": "Works on vision models and is building a robot arm at home.",
  "encounters": [
    {"time": "2026-11-21T15:00:00Z", "place": "Worn Hackathon", "notes": "Talked about RAG and robotics."}
  ],
  "topics": ["Robotics side project", "RAG evaluation"],
  "follow_ups": [
    {
      "text": "Send Sam the RAG article",
      "reason": "Alex promised to send it tonight.",
      "due_in_hours": 6,
      "channel": "message",
      "draft": "Hi Sam, here's the RAG article I mentioned: [add link]"
    }
  ],
  "avoid": ["Layoffs at Sam's last company"],
  "since_you_met": [],
  "shared_links": [],
  "spoken": [
    "Sam Rivera, ML engineer at Acme.",
    "Ask about the robotics side project.",
    "Alex promised to send the RAG article."
  ]
}
```

The server enforces these rules:

- `headline` and each item in `topics`, `follow_ups`, `avoid`, and `spoken` are at most 60 characters.
- `spoken` has 1 to 4 items, starts with the person's name, and contains nothing from `avoid`.
- `due_in_hours` is greater than 0 and at most 720.
- `channel` is `message`, `email`, or `none`.
- `draft` is at most 300 characters, contains no links or `avoid` content, and is empty when `channel` is `none`.
- `since_you_met` is never taken from the model.

## Phone hub

The hub accepts requests only from localhost or the configured glasses address. Other clients receive `403` with `{"ok": false, "reason": "untrusted_device"}`.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Returns `{"ok": true}` |
| `POST` | `/recognize` | Matches a JPEG in the multipart `image` field |
| `POST` | `/captures/ready` | Tells the phone a capture is ready to pull |

A successful `/recognize` response:

```json
{
  "match": true,
  "person_id": "p_7f3a",
  "capture_person_id": "p_7f3a",
  "score": 0.62,
  "card": {
    "name": "Sam Rivera",
    "headline": "ML engineer at Acme",
    "glance": {"name": "Sam Rivera", "line": "Send the RAG article"},
    "display_items": [
      {"label": "Talk about", "text": "Robotics side project"},
      {"label": "Avoid", "text": "Layoffs at Sam's last company"}
    ],
    "spoken_items": ["Sam Rivera, ML engineer at Acme."]
  }
}
```

Without a match, the response is `{"match": false, "reason": "<reason>"}`, where the reason is `no_match`, `no_face`, `multiple_faces`, `backend_unreachable`, or `invalid_image`.

`capture_person_id` appears only on a confident match, so a close call can't update the wrong person.

## Glasses

The glasses serve finished captures to the trusted phone.

| Method | Path | Returns |
| --- | --- | --- |
| `GET` | `/captures` | Finished captures not yet transferred |
| `GET` | `/captures/{id}/photos/{n}` | A JPEG, with `n` starting at 0 |
| `GET` | `/captures/{id}/audio` | The `.m4a` recording |
| `DELETE` | `/captures/{id}` | Deletes the capture after the phone saves it |
| `GET` | `/status` | Read-only device status |

```json
{
  "captures": [
    {
      "capture_id": "c_20261121_101503",
      "started_at": "2026-11-21T15:15:03Z",
      "ended_at": "2026-11-21T15:18:10Z",
      "photos": 3,
      "has_audio": true,
      "person_id": "p_7f3a"
    }
  ]
}
```

`person_id` is present only when a recording started from a confident match. Capture IDs must match `^[A-Za-z0-9_-]{1,64}$`.
