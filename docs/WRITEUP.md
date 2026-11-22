# Recall: Giving Smart Glasses a Memory for People

*Built by Don Yuen at Worn: Smart Glasses Hackathon, NYC, November 20 to 21, 2026.*

Recall helps you remember the people you meet. It records a conversation on smart glasses, turns it into a private profile on your phone, and brings that profile back the moment you see the person again.

## The problem

Networking runs on memory, and memory fails at the worst moment. You recognize someone across the room, but you can't place their name, what they work on, or what you promised to send them.

Contact apps don't solve this, because they need you to stop and type after every conversation. The hackathon's theme was "Second nature": wearable AI that works so naturally it disappears. I wanted the glasses to handle remembering, so the wearer could stay in the conversation.

## What I built

Recall is a three-part system that runs on the MicroLumin M6 glasses, an Android phone, and a small backend.

| Part | What it does |
| --- | --- |
| **Glasses app** | Listens offline for a greeting, takes photos, records audio, and shows a compact card |
| **Phone app** | Acts as the hub: syncs captures, matches faces, stores people, and schedules follow-ups |
| **Backend** | Creates face vectors, transcribes audio, and writes validated profiles, while storing nothing |

The core loop has four steps:

1. **Capture.** The wearer taps or says "Hello, nice to meet you." The glasses take three photos, announce the recording, and save the audio.
2. **Understand.** The phone sends the capture through three backend calls: face embedding, speaker-labeled transcription, and profile generation.
3. **Follow up.** If the conversation included a promise, Recall schedules a reminder with a draft. It never sends the message itself.
4. **Recognize.** When the wearer sees the person again, one tap shows their name, context, and anything to avoid.

## Key design decisions

**The phone is the hub.** The glasses have a small battery and overheat under sustained load, so they do no face processing. They capture still photos and audio, then hand off the work. People, profiles, and face vectors all live on the phone.

**The backend is stateless.** It has no database and doesn't log images, audio, or transcripts. Each endpoint does one short job, which also keeps every request under Cloudflare's 100-second limit.

**Recognition is closed-set.** Recall compares a face only against people the wearer has already saved. An unknown face returns "no match," and nothing is ever looked up online.

**The model is not trusted.** Each profile must pass server-side checks for structure, length, and content. A reply that fails goes back to the model with the specific error, up to two times. The server also owns fields the model shouldn't control, such as encounter history and "since you met" updates.

**Transcripts are treated as untrusted input.** Someone in a recording could say "ignore your instructions." The prompt keeps transcript text in escaped data blocks, separate from the rules.

**Recording is deliberate and audible.** The M6 has no recording light, so Recall plays a beep and says "Recording started." A voice-started recording is discarded unless a face is confirmed.

## What was hard

**Speaker attribution.** A profile is only useful if it describes the right person. Early tests put the wearer's own project into the other person's topics and added a feeling nobody had stated. I tightened the prompt and added server-side checks around those failures.

**Unreliable model metadata.** The transcription model produced good text and speaker labels, but it reported one 59-second clip as 68 seconds. The backend now measures duration from the audio itself and repairs timestamps.

**Connectivity on real hardware.** The original design linked the glasses to the phone over a Wi-Fi hotspot, but that link dropped during testing. I built a USB bridge script that restores the connection automatically when a cable is replugged, and made it the demo setup.

**Voice in a loud room.** Short wake words misfire at a busy event. Recall requires the full phrase "Hello, nice to meet you" and ignores greetings during a recording and for five seconds after it.

## Results

These results come from testing during the hackathon. The sample media was not captured on the M6, so real-world accuracy will differ.

| Check | Result |
| --- | --- |
| Backend test suite | 161 passed, 1 skipped (needs local ffmpeg) |
| End-to-end container checks | 27 of 27 passed |
| Face similarity, same person | 0.951 on a sample photo pair |
| Face similarity, different people | 0.027, against a 0.45 match threshold |
| Speaker labeling | 89% to 96% of speech correct across 3 runs on a real 30-second call |
| Speech coverage | At least 98% across the same 3 runs |

## What was simulated

- "Since you met" updates come from a fixture file, not live social media.
- Periodic profile refresh is not implemented.
- The iPhone app was not built.

## Responsible use

A product that recognizes faces and records conversations carries real risk, and a hackathon prototype doesn't resolve it.

- **Consent is unsolved.** Some US states require everyone's consent to record. A spoken notice is not consent, and a release would need a real consent flow.
- **Bystanders are a risk.** The microphone can't tell who said a phrase, so a nearby greeting could start a recording.
- **Face data is sensitive.** Laws such as Illinois BIPA regulate biometric data. Local storage and closed-set matching reduce the risk but don't remove it.
- **Third-party processing remains.** Audio and transcripts go to external speech and LLM providers.
- **Licensing limits commercial use.** InsightFace's pretrained models are for non-commercial research.

## What's next

1. Build a consent flow that works for both people.
2. Make the glasses-to-phone link reliable without a cable.
3. Support group conversations.
4. Replace the simulated refresh with links people choose to share.
5. Move to a commercially licensed face model.
6. Port the phone hub to iPhone.

## Built with

Python, FastAPI, Pydantic, InsightFace, ffmpeg, Docker, Kotlin, Jetpack Compose, Ktor, Room, WorkManager, NanoHTTPD, Vosk, GLM 5.3, MiMo v2.6 Pro, and Claude Code.
