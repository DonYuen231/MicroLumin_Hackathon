# Recall User Loop

Recall remembers the people you meet. You record a conversation on the glasses, the phone turns it into a profile, and the glasses remind you who the person is next time.

```text
Put on glasses -> Start recording -> Talk -> End recording
                                                  |
Meet them again <- Follow up <- Review profile <- Phone saves it
```

## Before you start

Check these once before a session:

- [ ] The laptop is on, with the backend and Cloudflare tunnel running.
- [ ] The Samsung's **Mobile Hotspot** is on and set to **WPA2-Personal**. The glasses can't join WPA3.
- [ ] **Recall** is open on the Samsung.
- [ ] Your name and the backend URL are filled in under **Recall > Settings**.
- [ ] For a live demo, **Demo speed** is on in **Recall > Settings**.
- [ ] The glasses show the ready screen:

```text
Recall                     ● Listening
--------------------------------------
Say
"Hello, nice to meet you"
or tap to record

--------------------------------------
Phone connected
```

Recall is now the Home screen on the glasses, so pressing Home returns here.

## The loop

### 1. Put on the glasses

Look for **● Listening** at the top right and **Phone connected** at the bottom. While it waits for the start phrase, Recall saves no audio.

### 2. Start recording

Tell the person you're recording, then use either option:

| Option | What to do | Needs |
| --- | --- | --- |
| **Tap (most reliable)** | Tap the touchpad once | Nothing else |
| **Voice** | Look at the person and say **"Hello, nice to meet you"** | Their face centered, and the phone connected |

You'll hear a beep and **"Recording started"**. The top right changes to a timer such as `REC 00:12`.

With voice, Recall deletes the audio and plays a low tone if it can't confirm a face within 15 seconds. It then goes back to listening.

With a tap, recording continues even if no face is found yet. The glasses show **Look at their face** and keep trying for about 20 seconds. A rising tone means the face was captured.

### 3. See who it is

| Result | What the glasses show | What gets saved |
| --- | --- | --- |
| **Someone you've met** | Their name and a short summary. Swipe for the full card, including follow-ups. | An update to their profile |
| **Someone new** | `New person` | A new profile |

### 4. Have the conversation

For the best profile, make sure these are said out loud:

- The person's **full name**. If the face isn't matched, Recall uses it to find their saved profile.
- What they do
- What you talked about
- Anything you promised, such as "I'll send you that article tonight"
- Anything they'd rather not discuss

### 5. End recording

Use any of these:

- Tap the touchpad once.
- Say **"Goodbye, see you next time"** after at least 5 seconds of recording.
- Stay silent for 20 seconds.
- Reach the 10-minute limit.

You'll hear two beeps and **"Recording finished"**. Recall returns to the ready screen by itself, so you don't need to reset anything. A tap can start the next recording right away, and voice works again after 5 seconds.

### 6. Let the phone save it

Keep the glasses on the hotspot. As soon as a recording ends, the glasses tell the phone to transfer it. A 30-second check is the backup. The phone then automatically:

1. Pulls the photos and audio
2. Reads the face
3. Transcribes the conversation
4. Writes or updates the profile
5. Deletes the capture from the glasses

Within about a minute, a notification says **Saved Sam Rivera** or **Updated Sam Rivera**.

### 7. Review the profile

Open **Recall > People** on the phone and tap the person. You can fix the name or edit any field, then tap **Save**.

### 8. Follow up

If the conversation included something worth following up on, the LLM picks the timing and writes a draft. When it's due, you'll get a notification like:

> **Follow up with Sam: send the RAG article**

Tap **Open draft** to open a message or email with the text filled in. Replace any `[add link]`, add the recipient, and send it yourself. Recall never sends anything automatically.

With Demo speed on, 1 hour passes in 10 seconds, and no follow-up takes longer than 1 minute.

### 9. Meet them again

Say the start phrase or tap to record. If Recall recognizes the person, their name and summary appear on the first screen.

To check who someone is without recording:

1. Hold the touchpad twice to reach **Recognize**.
2. Aim at their face and tap.
3. Read the name and summary, and swipe for more.
4. Hold to return to the ready screen.

## Controls

| Gesture | Ready screen | While recording | Recognize |
| --- | --- | --- | --- |
| **Tap** | Start recording | End recording | Scan a face |
| **Swipe** | Nothing | Show more of the card | Read the card |
| **Hold** | Next mode | Nothing | Next mode |

## Sounds

| You hear | Meaning |
| --- | --- |
| One beep | Recording began |
| "Recording started" | People nearby are being told it's recording |
| Rising tone | Face captured, or recognized someone you've met |
| Low tone | No face confirmed, so nothing was kept |
| Two beeps and "Recording finished" | Recording saved |

## If something goes wrong

| What you see | What to do |
| --- | --- |
| `Phone not connected` | Check that the hotspot is on and Recall is open on the Samsung. A tap still records. |
| `Not worn` | Put the glasses on. A tap still records. |
| No `● Listening` | Press Home on the glasses. |
| Voice doesn't start | Say the whole phrase clearly, or tap instead. |
| Low tone after the phrase | Center the person's face and try again. |
| No **Saved** notification | Open **Recall > Captures** and tap **Get captures from glasses**. |
| Capture failed on the phone | Check that the laptop is on, then use **Test backend** in Settings. If the tunnel restarted, update the backend URL. |

### adb commands

Run these in PowerShell from the repo root. Use `adb devices` to find `<glasses>`.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# Open Recall
& $adb -s <glasses> shell am start -n app.recall.glasses/.MainActivity

# Rejoin the hotspot
& $adb -s <glasses> shell am start -n app.recall.glasses/.MainActivity --es wifi_ssid "<hotspot name>" --es wifi_password "<hotspot password>"

# Restore the original Home screen
& $adb -s <glasses> shell cmd package set-home-activity <launcher package>/<launcher activity>

# Make Recall the Home screen again
& $adb -s <glasses> shell cmd package set-home-activity app.recall.glasses/.MainActivity
```

## Not proven on the M6 yet

Run these before the demo:

- [ ] A real tap starts and ends a recording.
- [ ] Hold and swipe work on the real touchpad.
- [ ] "Hello, nice to meet you" and "Goodbye, see you next time" work with real speech in the room.
- [ ] A tapped conversation creates a full profile.
- [ ] **Open draft** opens a filled-in message on the Samsung.
- [ ] A known person is recognized from a new photo. The 0.45 match threshold still needs tuning.
- [ ] `Not worn` appears when the glasses come off.
- [ ] Recall opens by itself after the glasses restart.
