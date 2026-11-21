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
- Capture starts on a spoken greeting or a click, and takes a few still photos, not video.
- Audio is recorded to local storage and sent later, not streamed live.
- The glasses do no heavy processing. The only speech work on the glasses is offline keyword spotting. No face detection or matching on the glasses.
- Every recording is announced out loud, because the M6 has no recording light.
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

### 5.0 Hands-free capture (the main flow)
The wearer opens Recall on the glasses once. There is no mode to pick.

1. While Recall is open, the glasses listen offline for the full phrase **"Hello, nice to meet you"**. Shorter greetings are ignored, because they misfire in a loud room. Nothing is saved while waiting.
2. On a greeting, the glasses beep, start saving audio right away so the person's name isn't lost, and take 3 photos from the center of the camera view. One photo goes to the phone's `/recognize`.
3. The result decides what happens:
   - **Match:** the glance card appears, and the recording updates that person.
   - **No match:** the recording becomes a new person.
   - **No face, several faces, or the phone can't be reached:** the audio is deleted, a low tone plays, and the glasses keep listening. Nothing is kept without a confirmed face.
4. Once a face is confirmed, the glasses say "Recording started".
5. Recording ends on the full phrase **"Goodbye, see you next time"**, after 20 seconds of silence, on a tap, or at 10 minutes. The glasses beep and say "Recording finished".
6. Greetings are ignored during a recording and for 5 seconds after one ends. Recall then returns to the ready screen by itself, so the next conversation needs no reset.

**Single tap:** on the ready screen, one tap on the touchpad starts a recording and the next tap ends it. A tap is the wearer's own decision, so it records even if no face is confirmed.

The phrases and timings are in `glasses-app/.../core/VoiceConfig.kt`. Click capture (5.1) and recognition (5.3) still work as the backup.

### 5.1 Capture mode (first meeting)
1. The user switches to Capture mode. A crosshair appears in the center of the display.
2. The user lines up the person's face in the crosshair and left-clicks.
3. The glasses take 3 photos (one camera is enough; use both only if the SDK makes it easy) and start recording audio. All files go into a new capture folder on the glasses.
4. When the conversation ends, the user left-clicks again to stop recording. The capture is saved and marked "not sent".
5. The user can start a new capture with the next person right away.

### 5.2 Transfer and processing (automatic)
1. When a recording ends, the glasses call `POST /captures/ready` on the phone, and the phone transfers it right away. The phone's background service also checks every 30 seconds as a backup, and "Get captures from glasses" stays as a manual backup.
2. The phone pulls every "not sent" capture from the glasses. The glasses delete each one after the phone confirms it. If the glasses can't be reached, the phone tries again on the next check. A capture that fails 3 times waits for the manual button.
3. For each capture, the phone calls the backend in order:
   1. `POST /v1/faces/embed` for each photo, which returns face vectors.
   2. `POST /v1/audio/transcribe`, which returns a transcript with speaker labels.
   3. `POST /v1/profiles/build`, which returns a profile.
4. The phone checks whether the new face vectors match someone already saved:
   - If yes, the new conversation updates that person's profile.
   - If no, it creates a new person.
   - If the capture carries a `person_id` from a confident hands-free match, it updates that person directly.
   - If the face doesn't match, the **full name** said in the conversation is the backup. It needs at least two words and exactly one saved person with that name. A photo that clearly shows someone else (similarity under 0.2) blocks the match.
5. The phone saves everything locally and shows a notification such as "Saved Sam Rivera" or "Updated Sam Rivera". The user can rename or fix anything on the phone.
6. The phone schedules each follow-up the LLM chose (section 8). When one is due, a notification such as "Follow up with Sam: send the RAG article" offers **Open draft**, which opens a message or email with the text filled in. Nothing is ever sent automatically. In Settings, **Demo speed** makes 1 hour pass in 10 seconds, with a 1-minute maximum per follow-up.

### 5.3 Recognition mode (meeting someone again)
1. The user switches to Recognize mode, aims the crosshair at the face, and left-clicks.
2. The glasses take 1 photo and send it to the phone: `POST /recognize` on the phone app.
3. The phone sends the photo to the backend (`/v1/faces/embed`) and gets a vector back.
4. The phone compares that vector to every saved vector (cosine similarity) and picks the best match above the threshold.
5. The phone answers the glasses with that person's profile card, or "no match".
6. The glasses first show only the name and a short summary. Scrolling opens the rest of the card.
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
  "capture_person_id": "p_7f3a",
  "score": 0.62,
  "card": {
    "name": "Sam Rivera",
    "headline": "ML engineer at Acme",
    "glance": {"name": "Sam Rivera", "line": "Send the RAG article"},
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

`glance.line` is the person's summary, up to 150 characters. Without a usable summary, it falls back to the first open follow-up, then the first topic, then the headline. It never shows an avoid item. `capture_person_id` appears only on a confident match. It is left out for a close call between two people and for the stage backup card, so a recording can't update the wrong person.

### 7.2 Glasses app endpoints (called by the phone)

The glasses app runs its own small HTTP server on port 8081, so the phone can pull captures. The phone finds the glasses' address in the hotspot's client list, or the user types it once in the phone's settings.

**`GET /captures`** lists captures not yet sent:
```json
{"captures": [
  {"capture_id": "c_20261121_101503", "started_at": "...", "ended_at": "...", "photos": 3, "has_audio": true, "person_id": "p_7f3a"}
]}
```
`person_id` is optional. It is present when a hands-free recording started from a confident match.

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
  "follow_ups": [
    {
      "text": "Send Sam the RAG article",
      "reason": "Don promised to send it tonight.",
      "due_in_hours": 6,
      "channel": "message",
      "draft": "Hi Sam, here's the RAG article I mentioned: [add link]"
    }
  ],
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
- `follow_ups` is empty unless a follow-up is genuinely worth doing. The LLM picks `due_in_hours` (up to 720) and `channel` (`message`, `email`, or `none`).
- `draft` is short, plain, and in first person as the wearer, at most 300 characters. It is empty when `channel` is `none`. It never contains a link (it uses `[add link]`) or anything from "avoid".
- Older profiles that store follow-ups as plain strings still load.

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
  - **Captures:** automatic sync progress, plus "Get captures from glasses" as a backup.
  - **Settings:** backend URL, app token, glasses address, wearer name, match threshold, and Demo speed.
- **Background server:** runs as a foreground service with a notification, so Android doesn't stop it while the phone is in a pocket. It also runs the 30-second capture check.
- **Follow-ups:** scheduled with WorkManager. A follow-up is skipped if its person was deleted or it was removed from the profile.
- **iPhone:** not built. Keep the network and storage code separate from the screens, so an iOS port is a clear next step for the pitch.

## 11. Glasses app details (for the glasses teammate)

- **Screen:** green-only, so use high contrast, large text, at most 5 lines visible, and the scroll wheel to move.
- **Hands-free:** listens with Vosk (`vosk-model-small-en-us-0.15`) whenever Recall is open, as described in 5.0.
- **Recording notice:** a beep, a short vibration, and a bundled "Recording started" or "Recording finished" clip. These are audio files because the M6 has no text-to-speech engine.
- **Modes:** Home, Capture, Recognize. A right click cycles through them. They remain as the click backup.
- **Capture:** 3 photos and AAC audio into a capture folder. Show a small "REC" and the elapsed time while recording.
- **Recognize:** 1 photo, `POST` it to the phone's `/recognize`, then show the glance or "No match". Scrolling opens the full card.
- **Server:** serve captures to the phone (section 7.2), or use the push fallback.
- **Voice:** use Android text-to-speech when the display is off, plus the sound cues from 5.4.
- **Phone address:** find the phone at the Wi-Fi gateway address.
- **Battery and heat:** camera on only during capture, no background work, screen dim when idle. The mic and keyword listener run only while Recall is on screen.

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

### Glasses app
Install over adb. The speech model (41 MB download) is pushed once and is not in Git or the APK.
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Push-Location glasses-app; .\gradlew.bat assembleDebug; Pop-Location
& $adb -s <glasses> install -r -g glasses-app\app\build\outputs\apk\debug\app-debug.apk
# Unzip https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip first
& $adb -s <glasses> push vosk-model-small-en-us-0.15 /sdcard/Android/data/app.recall.glasses/files/
& $adb -s <glasses> shell am start -n app.recall.glasses/.MainActivity
```
Without the model, Recall still runs with click controls. The top right of the screen says "Listening" when hands-free is active.

The M6 has no usable Wi-Fi screen, so a debug build joins the phone's hotspot over adb. The network is saved for next time:
```powershell
& $adb -s <glasses> shell am start -n app.recall.glasses/.MainActivity --es wifi_ssid "<hotspot name>" --es wifi_password "<hotspot password>"
```

### USB link (current demo setup)
The hotspot link dropped during testing, so the demo runs with the glasses and phone both plugged into the laptop. Run this in Git Bash and leave it open:
```bash
sh scripts/usb-bridge.sh
```

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
- Voice trigger: the mic can't tell who said a greeting. A nearby "hello" can start a recording if a face is in view, including a conversation the wearer isn't part of, which one-party consent doesn't cover. The spoken notice makes recording obvious, but it isn't consent.
- Keyword spotting can mishear speech in a loud room, so a recording can start or stop by mistake.
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

