# How to Use Recall

Recall helps you remember the people you meet. You record a conversation on the MicroLumin M6 glasses, your phone turns it into a profile, and the next time you meet that person, the glasses show who they are and what you talked about.

```text
Start recording -> Talk -> End recording -> Phone saves a profile
       ^                                            |
       |                                            v
Meet them again <------- Follow up <------- Review the profile
```

**Always tell the other person you're recording before you start.** The glasses have no recording light, so they announce each recording out loud, but that doesn't replace asking.

## Before you start

You need three devices:

| Device | What it does | What should be running |
| --- | --- | --- |
| **M6 glasses** | Records and shows the card | Recall, which opens as the Home screen |
| **Samsung phone** | Connects everything and stores profiles | Mobile Hotspot and the Recall app |
| **Laptop** | Builds the profiles | The backend and Cloudflare tunnel |

Check these in order:

1. **Laptop:** Make sure it's awake and the backend and tunnel are running. See [Commands for the setup person](#commands-for-the-setup-person).
2. **Phone hotspot:** Open **Settings > Connections > Mobile Hotspot and Tethering > Mobile Hotspot** and turn it on. Security must be **WPA2-Personal**, because the glasses can't join WPA3.
3. **Phone app:** Open **Recall** and leave the phone unlocked.
4. **Glasses:** Put them on. If you don't see the ready screen below, press the Home button.

```text
Recall                     ● Listening
--------------------------------------
Say
"Hello, nice to meet you"
or tap to record

--------------------------------------
Phone connected
```

You're ready when you see **● Listening** and **Phone connected**. The glasses don't need a USB cable.

## The loop

### 1. Start recording

Face the person from about 3 feet away, with only their face in view.

**Tap the touchpad once** and hold your head still for 2 seconds. This is the most reliable way to start.

You can also say **"Hello, nice to meet you"**. Voice only keeps the recording if the glasses confirm a face and can reach the phone.

You'll hear a beep and **"Recording started"**, and a timer appears at the top right.

### 2. Check the screen

| The glasses show | What it means | What to do |
| --- | --- | --- |
| **A name and summary** | Recall knows this person | Swipe to see topics, follow-ups, and what to avoid |
| **New person** | This is a first meeting | Keep talking |
| **Look at their face** | No face found yet | Center them and hold still until you hear a rising tone |

### 3. Have the conversation

Recall builds the profile from what's said out loud. For the best result, make sure the conversation includes:

- Their **full name**, such as "I'm Sam Rivera"
- What they do
- What you talked about
- Anything you promised, such as "I'll send you that article tonight"
- Anything they'd rather not discuss

### 4. End recording

**Tap the touchpad once**, or say **"Goodbye, see you next time"** after at least 5 seconds.

Recording also stops after 20 seconds of silence or at 10 minutes.

You'll hear two beeps and **"Recording finished"**. The glasses return to the ready screen by themselves, so you can start the next conversation right away.

### 5. Check the phone

The recording transfers automatically. Keep the glasses on the hotspot and wait, usually less than a minute, for one of these notifications:

- **Saved Sam Rivera** for someone new
- **Updated Sam Rivera** for someone you've met before

Open **Recall > People** and tap the person to read or correct their profile. Tap **Save** if you change anything.

### 6. Follow up

If you made a promise or there's a reason to reconnect, Recall picks a time and writes a draft. When it's due, you'll get a notification like:

> **Follow up with Sam: send the RAG article**

Tap **Open draft**, add the recipient, replace any `[add link]`, and send it yourself. **Recall never sends anything for you.**

For a live demo, turn on **Recall > Settings > Demo speed**. One hour then passes in 10 seconds, and every follow-up appears within a minute.

### 7. Meet them again

Tap once while facing them. You'll hear a rising tone and see their name and summary. The new conversation is added to their existing profile.

If the face doesn't match, Recall uses the full name from the conversation to find their profile.

## Controls and sounds

| Touchpad | On the ready screen | While recording |
| --- | --- | --- |
| **Tap** | Start recording | End recording |
| **Swipe** | Nothing | Show more of the card |

| You hear | Meaning |
| --- | --- |
| One beep, then "Recording started" | Recording has begun |
| Rising tone | Face captured or person recognized |
| Low tone | No face confirmed, so a voice-started recording was discarded |
| Two beeps, then "Recording finished" | Recording saved |

## If something goes wrong

| Problem | What to do |
| --- | --- |
| **Phone not connected** | Check that the hotspot is on and Recall is open on the phone. Tapping still records, and the recording transfers when the link returns. |
| **Settings shows "A device tried to connect"** | Tap **Trust this device as the glasses** on the phone. |
| **No ● Listening** | Press Home on the glasses. |
| **Voice doesn't start** | Tap instead. |
| **Name doesn't appear** | Face them from about 3 feet in good light and hold still. It may take a few seconds if Wi-Fi hiccups. |
| **"Unknown" in People** | The name wasn't clear. Open the profile, fix the name, and tap **Save**. |
| **No Saved notification after a minute** | Open **Recall > Captures** and tap **Get captures from glasses**. |
| **A capture fails on the phone** | In **Settings**, tap **Test backend**. If the tunnel restarted, paste in its new URL. |
| **You recorded by mistake** | Ask the setup person to delete it right away, before it transfers if possible. To remove a saved profile, open it and tap **Delete person**. |

## Known limits

- Recall handles one person per conversation.
- Faces need decent light and a steady view. Blurry photos may not match.
- Brief Wi-Fi drops can delay recognition during a conversation.
- In a loud room, the voice phrases can be missed, so use the tap.
- A nearby voice can't be told apart from yours. Only record conversations you're part of.

## Commands for the setup person

Run these from the repo root in PowerShell.

**Start the backend and tunnel:**

```powershell
cd backend
docker compose up -d --build
cloudflared tunnel --url http://localhost:8000
```

If the tunnel URL changes, paste it into **Recall > Settings > Backend URL** and tap **Test backend**.

**Install the glasses app:**

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
Push-Location glasses-app; .\gradlew.bat assembleDebug; Pop-Location
& $adb -s <glasses> install -r -g glasses-app\app\build\outputs\apk\debug\app-debug.apk
```

**First-time setup only.** Unzip [the speech model](https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip), then push it and make Recall the Home screen:

```powershell
& $adb -s <glasses> push vosk-model-small-en-us-0.15 /sdcard/Android/data/app.recall.glasses/files/
& $adb -s <glasses> shell cmd package set-home-activity app.recall.glasses/.MainActivity
```

**Join the glasses to the hotspot.** The M6 has no usable Wi-Fi screen. Get the hotspot name and password from the team, and don't commit them:

```powershell
& $adb -s <glasses> shell am start -n app.recall.glasses/.MainActivity --es wifi_ssid "'<hotspot name>'" --es wifi_password "'<hotspot password>'"
```

Recall then keeps the glasses on that hotspot while it's on screen.

**Restore the original Home screen:**

```powershell
& $adb -s <glasses> shell cmd package set-home-activity <launcher package>/<launcher activity>
```
