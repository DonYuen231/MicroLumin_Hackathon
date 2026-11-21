# Recall: a networking memory for smart glasses

Working name. Built for Worn: Smart Glasses Hackathon, NYC, Nov 20 to 21, 2026.

Recall remembers the people you meet. You capture a face and record the conversation on the glasses. Later, the phone and the backend turn that into a profile. The next time you see that person, you aim at their face and click, and the glasses show their name, what you talked about, and what to bring up or avoid. No more forgetting faces at networking events.

This README is the spec. It is the contract between the three apps and the instructions for Claude Code, which writes the backend and the phone app. Read the whole file before writing any code.

---

## 1. Hackathon context

- **Theme:** "Second nature." Wearable AI that understands you, works across your devices, and responds through the glasses so naturally it disappears.
- **Track:** Seamless Handoff (proposed, not final). The phone is the hub, and every recognition is a glasses to phone to glasses handoff.
- **Scoring:** five equal criteria, each 0, 1, or 2, for a total out of 10:
  - Understands you
  - Adaptability (phone-to-glasses flow)
  - Ergonomics (less effort scores higher)
  - Works on the glasses
  - Impact and pitch
- **Hard rule:** the project must run on the MicroLumin M6 glasses. A team averaging below 1 on "Works on the glasses" can't win a prize.
- **Deadline:** code freeze and submission at **2:00 PM Saturday**. Demo expo at 2:15 PM. Finalists demo on stage for 3 minutes, wearing the glasses, plus 2 minutes of Q&A.

## 2. The three parts

| Part | Platform | Built by | Job |
| --- | --- | --- | --- |
| Glasses app | Android, MicroLumin M6 | Glasses teammate | Capture photos and audio, show or speak profiles, take ring input |
| Phone app | Native Android, Kotlin | Claude Code (Don reviews and runs it) | The hub. Receives captures from the glasses, calls the backend, matches faces, stores all profiles, sends profile cards to the glasses |
| Backend | Python 3.12, FastAPI, Docker | Claude Code (Don reviews and runs it) | Stateless processing: face vectors, transcription, and profile writing with an LLM. Stores nothing. |

An iPhone version of the phone app is planned for later. It is not built for the hackathon and appears in the pitch only.

```
 [Ring (mouse)] --Bluetooth--> [M6 glasses]
                                   |
                       Wi-Fi (phone's hotspot), HTTP
                                   |
                              [Phone app]  <-- the hub, holds all data
                                   |
                       HTTPS (Cloudflare Tunnel), bearer token
                                   |
                          [Backend on Don's laptop]
                          /          |          \
                   InsightFace   Speech-to-text   NanoGPT LLM
```

## 3. Hardware facts and limits

- The M6 runs basic Android. Full tech specs were never provided.
- Two forward-facing color cameras, a microphone, speakers, and a small touchpad on the right side.
- Green-only display, and the display is removable. A color display is planned for later.
- Small battery and a real overheating problem. No continuous capture or streaming. No video.
- 32 GB internal storage.
- No recording indicator light.

Design rules that follow from this:
- Capture only on a click, and capture a few still photos, not video.
- Audio is recorded to local storage and sent later, not streamed live.
- The glasses do no heavy processing. No face detection or matching on the glasses.
- The glasses delete a capture's files once the phone confirms it received them.

## 4. Input

The real product uses a ring on the index finger with two side-by-side parts: a spinning disk and a fixed base with buttons. Both can be clicked. For the hackathon, a Bluetooth mouse stands in for the ring.

| Ring action | Mouse | Touchpad fallback | Action |
| --- | --- | --- | --- |
| Spin the disk | Scroll wheel | Swipe up or down | Scroll the profile, or move to the next spoken item |
| Click the disk | Left click | Tap | Capture (in capture or recognition mode), or repeat the current spoken item |
| Click the base | Right click | Long press | Switch modes: Home, Capture, Recognize |

Every action must also work on the built-in touchpad, in case the M6 won't pair with a mouse.

## 5. User flows

### 5.1 Capture mode (first meeting)
1. The user switches to Capture mode. A crosshair appears in the center of the display.
2. The user lines up the person's face in the crosshair and left-clicks.
3. The glasses take 3 photos (one camera is enough; use both only if the SDK makes it easy) and start recording audio. All files go into a new capture folder on the glasses.
4. When the conversation ends, the user left-clicks again to stop recording. The capture is saved and marked "not sent".
5. The user can start a new capture with the next person right away.

### 5.2 Transfer and processing (later, at home or when ready)
1. On the phone, the user taps "Get captures from glasses". The glasses must be on the phone's hotspot.
2. The phone pulls every "not sent" capture from the glasses. The glasses delete each one after the phone confirms it.
3. For each capture, the phone calls the backend in order:
   1. `POST /v1/faces/embed` for each photo, which returns face vectors.
   2. `POST /v1/audio/transcribe`, which returns a transcript with speaker labels.
   3. `POST /v1/profiles/build`, which returns a profile.
4. The phone checks whether the new face vectors match someone already saved:
   - If yes, the new conversation updates that person's profile.
   - If no, it creates a new person.
5. The phone saves everything locally. The user can rename or fix anything on the phone.

### 5.3 Recognition mode (meeting someone again)
1. The user switches to Recognize mode, aims the crosshair at the face, and left-clicks.
2. The glasses take 1 photo and send it to the phone: `POST /recognize` on the phone app.
3. The phone sends the photo to the backend (`/v1/faces/embed`) and gets a vector back.
4. The phone compares that vector to every saved vector (cosine similarity) and picks the best match above the threshold.
5. The phone answers the glasses with that person's profile card, or "no match".
6. The glasses show the card, and the user scrolls to read it before walking over.
7. The user can switch to Capture mode right away to record this new conversation.

**Closed set only.** The app can only recognize people the user already saved. An unknown face returns "no match", and nothing is ever looked up by face.

### 5.4 Screenless mode (display removed)
- The glasses app checks at start whether the display is attached. If it isn't, it uses voice.
- The profile card's `spoken` items are read aloud with Android text-to-speech. The scroll wheel moves between items, and a left click repeats the current one.
- Sound cues:
  - one tone when recording starts
  - two tones when it stops
  - a rising tone for a match
  - a low tone for no match
- Aiming without a crosshair: the photo is taken from the center of the camera view. If the backend finds no face, or more than one, the glasses say "No face found, try again."
- "Avoid" items are never read aloud unless the user scrolls to them, so people nearby don't hear them.
- If the M6 has no text-to-speech engine, the phone asks the backend for an MP3 (`POST /v1/tts`, stretch goal) and passes it to the glasses.

### 5.5 Profile refresh (faked for the demo)
In the real product, the phone periodically asks the backend to refresh each profile from the social media links that person shared with the user, and fills in "Since you met". For the hackathon, `/v1/profiles/refresh` returns canned updates from a fixtures file.

## 6. Networking

- The phone turns on its Wi-Fi hotspot, and the glasses join it. The phone keeps using cellular data, so everything still reaches the internet.
- **The glasses find the phone** at the hotspot's gateway address. The glasses app reads the default gateway from its Wi-Fi connection, so the address doesn't need to be hardcoded, since newer Android versions change the hotspot subnet.
- **The phone app runs a small HTTP server** on port 8080 (Ktor or NanoHTTPD) that the glasses call.
- **Plain HTTP on the hotspot:** Android blocks plain HTTP by default. Both apps need a network security config that allows plain HTTP to local addresses.
- **The phone reaches the backend** through a Cloudflare Tunnel HTTPS URL. A quick tunnel's URL changes every time it starts, so the backend URL is a setting in the phone app, not a constant.
- Cloudflare drops requests that take over 100 seconds. That's why processing is split into separate short calls and no single endpoint does everything.

## 7. API contract

All JSON uses snake_case. All times are ISO 8601 in UTC. Images are JPEG, at most 1280 px on the long side and under 1 MB each. Audio is AAC in an .m4a file, mono, 16 kHz.

### 7.1 Phone app endpoints (called by the glasses)

Base: `http://<hotspot gateway>:8080`

**`GET /health`** returns `{"ok": true}`.

**`POST /recognize`**: multipart form, field `image` (one JPEG).

Match:
```json
{
  "match": true,
  "person_id": "p_7f3a",
  "score": 0.62,
  "card": {
    "name": "Sam Rivera",
    "headline": "ML engineer at Acme",
    "display_items": [
      {"label": "Last met", "text": "Worn Hackathon, Nov 21, 2026"},
      {"label": "Talk about", "text": "Her robotics side project"},
      {"label": "Follow up", "text": "You said you'd send the RAG article"},
      {"label": "Since you met", "text": "Started a new role at Acme"},
      {"label": "Avoid", "text": "Her last company's layoffs"}
    ],
    "spoken_items": [
      "Sam Rivera, ML engineer at Acme.",
      "Ask about her robotics side project.",
      "You promised to send the RAG article.",
      "Avoid: her last company's layoffs."
    ]
  }
}
```

No match:
```json
{"match": false, "reason": "no_match"}
```
`reason` can also be `no_face`, `multiple_faces`, or `backend_unreachable`.

### 7.2 Glasses app endpoints (called by the phone)

The glasses app runs its own small HTTP server on port 8081, so the phone can pull captures. The phone finds the glasses' address in the hotspot's client list, or the user types it once in the phone's settings.

**`GET /captures`** lists captures not yet sent:
```json
{"captures": [
  {"capture_id": "c_20261121_101503", "started_at": "...", "ended_at": "...", "photos": 3, "has_audio": true}
]}
```

**`GET /captures/{capture_id}/photos/{n}`** returns a JPEG, where n starts at 0.

**`GET /captures/{capture_id}/audio`** returns the .m4a file.

**`DELETE /captures/{capture_id}`**: the phone calls this after it has saved everything. The glasses delete the files.

If running a server on the glasses turns out to be hard, the fallback is reversed: the glasses `POST` each capture to the phone at `POST /captures` (multipart: `capture_id`, `started_at`, `ended_at`, `photo_0..2`, `audio`), and delete it after a 200 response.

### 7.3 Backend endpoints (called by the phone)

Base: the Cloudflare Tunnel URL. Every request needs `Authorization: Bearer <APP_TOKEN>`. Errors use one JSON shape (RFC 9457 problem details): `{"type", "title", "status", "detail"}`.

**`GET /healthz`** returns `{"ok": true}` with no auth needed.

**`POST /v1/faces/embed`**: multipart, field `image`.
```json
{"faces": [{"bbox": [x1, y1, x2, y2], "det_score": 0.98, "embedding": [512 floats, L2-normalized]}]}
```
Returns 422 with `title: "no_face"` or `"multiple_faces"` when there isn't exactly one clear face. When there are several faces, use the largest face near the center if it is clearly bigger than the rest. Otherwise return `multiple_faces`.

**`POST /v1/audio/transcribe`**: multipart, field `audio`.
```json
{
  "duration_s": 184.2,
  "segments": [
    {"speaker": "A", "start": 0.0, "end": 4.1, "text": "Hi, I'm Sam."},
    {"speaker": "B", "start": 4.3, "end": 6.0, "text": "Nice to meet you, I'm Don."}
  ]
}
```

**`POST /v1/profiles/build`**: JSON.
```json
{
  "transcript": {"segments": [...]},
  "wearer_name": "Don",
  "existing_profile": null,
  "met_at": {"time": "...", "place": "Worn Hackathon"},
  "shared_links": ["https://www.linkedin.com/in/..."]
}
```
Returns a profile (section 8). When `existing_profile` is set, the LLM merges the new conversation into it and doesn't start over. The LLM works out which speaker is the wearer from `wearer_name` and the conversation.

**`POST /v1/profiles/refresh`**: JSON `{"person_id", "name", "shared_links", "profile"}`. Returns `{"since_you_met": [...]}`. **Faked for the demo:** it reads `fixtures/refresh.json`, keyed by name.

**`POST /v1/tts`** (stretch): JSON `{"text"}`. Returns `audio/mpeg`. Only needed if the M6 has no text-to-speech engine.

## 8. Profile format

The backend returns this, and the phone stores it.
```json
{
  "name": "Sam Rivera",
  "headline": "ML engineer at Acme",
  "summary": "Met at the Worn Hackathon. Works on vision models, building a robot arm at home.",
  "encounters": [{"time": "...", "place": "Worn Hackathon", "notes": "Talked about RAG and robotics."}],
  "topics": ["robotics side project", "RAG evaluation"],
  "follow_ups": ["Send the RAG article Don mentioned"],
  "avoid": ["Her last company's layoffs"],
  "since_you_met": [],
  "shared_links": [],
  "spoken": [
    "Sam Rivera, ML engineer at Acme.",
    "Ask about her robotics side project.",
    "You promised to send the RAG article."
  ]
}
```
Rules for the LLM:
- Use only what is in the transcript or the existing profile. Never invent facts.
- If the name is not clear, use `"name": "Unknown"` so the user can fix it on the phone.
- Keep each item short enough for a small green display, at most about 60 characters.
- `spoken` holds at most 4 items and takes under about 20 seconds to read. The name always comes first, and "avoid" items are never included.

The phone builds the glasses card (section 7.1) from this profile.

## 9. Backend details

- **Stack:** Python 3.12, uv, FastAPI, Pydantic v2, httpx, pydantic-settings, pytest, ruff.
- **Face vectors:** InsightFace with the `buffalo_l` model pack (detector plus ArcFace recognition, 512-dimension vectors), running on CPU with onnxruntime.
  - Install it inside Docker. pip install of InsightFace often fails on Windows.
  - Download the model files when the image is built, not on the first request.
  - **License note:** the InsightFace code is MIT, but its pretrained models are for non-commercial research only. Fine for a hackathon. A product would need a commercial license or another model.
- **LLM:** NanoGPT, using Don's Pro subscription, through its OpenAI-compatible API.
  - Set the API key to "subscription only" in NanoGPT settings, so nothing is billed to the balance.
  - The Pro plan covers open-weight models only (for example Qwen, DeepSeek, GLM). Pick one that follows JSON well, and test it first.
  - Validate every LLM reply with Pydantic. If it doesn't parse, retry up to 2 times with the error included.
  - The base URL and model names come from settings. Check them in the NanoGPT docs: `GET /api/v1/models?detailed=true`, and the subscription model list.
- **Speech-to-text:** first choice is NanoGPT's transcription endpoint with speaker labels (ElevenLabs STT), if the Pro plan covers it. Backup is Deepgram pre-recorded transcription with diarization (free $200 credit). The provider is a setting.
- **Stateless:** no database, and nothing written to disk. Images and audio are never logged. Logs hold only request IDs, timings, and errors.
- **Security:**
  - A bearer token on every endpoint except `/healthz`.
  - HTTPS through the tunnel.
  - Size limits: 2 MB per image, 50 MB per audio file.
  - Timeouts and retries with backoff on every outside call.
  - API keys live only in the backend's `.env` file. They never go in the phone app or in git.
- **Config (`.env`):** `APP_TOKEN`, `NANOGPT_API_KEY`, `NANOGPT_BASE_URL`, `LLM_MODEL`, `STT_PROVIDER`, `DEEPGRAM_API_KEY`, `FACE_MODEL=buffalo_l`. Commit a `.env.example`. Never commit `.env`.

## 10. Phone app details

- **Stack:** Kotlin, Jetpack Compose, Ktor client and server (or NanoHTTPD for the server), Room for storage, minimum Android 10.
- **Storage:** people, profiles, encounters, and face vectors all live in Room on the phone. Keep up to 10 vectors per person (all enrollment photos) and match against the best one.
- **Matching:** cosine similarity between L2-normalized vectors, which is a dot product. Start the threshold at 0.45 and tune it on the actual M6 photos before the demo. If the top two people score within 0.05 of each other, show "Is this Sam?" and let the user confirm.
- **Screens:**
  - **People list:** name, last met, and photo thumbnail.
  - **Person detail:** edit any field, plus a Delete person button.
  - **Captures:** "Get captures from glasses", then progress for each step.
  - **Settings:** backend URL, app token, glasses address, wearer name, and match threshold.
- **Background server:** runs as a foreground service with a notification, so Android doesn't stop it while the phone is in a pocket.
- **iPhone:** not built. Keep the network and storage code separate from the screens, so an iOS port is a clear next step for the pitch.

## 11. Glasses app details (for the glasses teammate)

- **Screen:** green-only, so use high contrast, large text, at most 5 lines visible, and the scroll wheel to move.
- **Modes:** Home, Capture, Recognize. A right click cycles through them.
- **Capture:** 3 photos and AAC audio into a capture folder. Show a small "REC" and the elapsed time while recording.
- **Recognize:** 1 photo, `POST` it to the phone's `/recognize`, then show the card or "No match".
- **Server:** serve captures to the phone (section 7.2), or use the push fallback.
- **Voice:** use Android text-to-speech when the display is off, plus the sound cues from 5.4.
- **Phone address:** find the phone at the Wi-Fi gateway address.
- **Battery and heat:** camera on only during capture, no background work, screen dim when idle.

## 12. Running it

### Backend on the laptop (main setup)
```bash
cd backend
cp .env.example .env        # fill in the keys
docker compose up --build   # API on http://localhost:8000
cloudflared tunnel --url http://localhost:8000
```
Copy the `https://<random>.trycloudflare.com` URL into the phone app's settings. If the tunnel restarts, the URL changes, so update the setting.

### Cloud hosting (optional, only if there is time)
The same Docker image can deploy to Google Cloud Run. This needs a Google Cloud account with a card on file and a budget alert.
- Set min and max instances to 1, so the face model is already loaded and there's no cold start.
- Raise the request timeout.
- Keep requests under Cloud Run's 32 MiB limit, which the audio format above already does.

Do this only after the full demo works on the laptop.

### Phone app
Open `phone-app/` in Android Studio, connect the phone with USB debugging on, and press Run. Or run `./gradlew installDebug`.

### Screen mirroring for the demo
The audience can't see through the glasses, so mirror both screens to a laptop with `scrcpy` over adb: one window for the glasses and one for the phone. Test this early.

## 13. Demo plan

1. **Before 10 AM:** do a real "first meeting" capture with a teammate and process it all the way through, so that person is saved. Seed two or three more people with fixture profiles.
2. **On stage:**
   - Show the people list on the phone.
   - Put on the glasses and aim at the teammate. The profile card appears on the mirrored glasses screen.
   - Scroll through it.
   - Detach the display and recognize again. The glasses speak the card.
   - Start a new capture to show the loop.
3. **Backups:** if the backend or the tunnel fails, the phone returns a cached card for the seeded teammate, so recognition still shows on the glasses.

**Faked for the demo, and said so in the writeup:**
- "Since you met" updates from social media
- Periodic profile refresh
- The iPhone app

## 14. Known limits (for the writeup)

- One person per conversation. Group conversations are not supported yet.
- No full video, by design, to save battery and limit heat.
- Recording laws: New York requires only one party's consent to record a conversation you're part of, but some states require everyone's consent. The product would need a consent feature before release, and the M6 has no recording light.
- Face data of other people is biometric data, which some states regulate (Illinois BIPA, for example). Closed-set matching and keeping all data on the user's phone limit this, but don't remove it.
- Social media lookups only use links the person chose to share, and must follow each platform's terms.
- InsightFace's pretrained models are non-commercial. A product would need a licensed model.
- Matching accuracy depends on lighting and angle. The threshold was tuned on a few faces only.

## 15. Build order (deadline 2:00 PM)

| By | Glasses teammate | Don and Claude Code |
| --- | --- | --- |
| 4 AM | Confirm on the M6: mouse pairing, touchpad events, camera, mic, text-to-speech, adb, scrcpy | Backend skeleton in Docker, `/healthz`, Cloudflare Tunnel running. NanoGPT key tested, and a model picked. |
| 7 AM | Capture mode saves photos and audio. Crosshair and modes work. | `/v1/faces/embed`, `/v1/audio/transcribe`, `/v1/profiles/build` working with test files |
| 9 AM | Recognize mode calls the phone and shows a card | Phone app: settings, storage, pulling captures, processing, `/recognize` server, matching |
| 10 AM | Full loop on the device | Seed the demo: real capture of a teammate, plus fixture profiles. Tune the threshold. |
| 12 PM | Screenless mode and sound cues | Faked refresh, cached-card backup, README results, demo video |
| 1:30 PM | Freeze. Rehearse the 3-minute demo twice. | Freeze. Submit. |

## 16. Open questions

- Final track: Seamless Handoff or Personal Insight?
- Final project name.
- Does the NanoGPT Pro plan cover the transcription endpoint?
- Can the glasses app run a small HTTP server, or do we use the push fallback?
- Add a "my goals" field for the wearer, so suggestions fit what they want from each contact?

## 17. Repo layout

```
backend/      FastAPI service, Dockerfile, docker-compose.yml, tests/, fixtures/
phone-app/    Android Studio project (Kotlin)
glasses-app/  Android Studio project for the M6 (glasses teammate)
docs/         Demo script, writeup, screenshots
README.md     This file
```
