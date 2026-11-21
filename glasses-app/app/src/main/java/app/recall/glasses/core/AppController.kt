package app.recall.glasses.core

import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor

fun isAvoidItem(text: String): Boolean = text.trimStart().startsWith("Avoid:", ignoreCase = true)

/** The position in a card's spoken items. `Avoid:` items are only read when scrolled to. */
class SpokenCursor(val items: List<String>) {
    var index = 0

    fun autoItems(): List<Pair<Int, String>> =
        items.mapIndexed { i, text -> i to text }.filterNot { isAvoidItem(it.second) }

    fun current(): Pair<Int, String>? = items.getOrNull(index)?.let { index to it }

    /** Returns false when the user scrolled past the last item. */
    fun move(delta: Int): Boolean {
        val target = index + delta
        if (target >= items.size) return false
        index = target.coerceAtLeast(0)
        return true
    }
}

/** All app behavior. Every public method and callback runs on the main thread. */
class AppController(
    private val camera: CameraSource,
    private val audio: AudioSource,
    private val store: CaptureStore,
    private val phone: PhoneApi,
    private val speaker: Speaker,
    private val cues: Cues,
    private val pairing: MousePairing,
    private val ui: Ui,
    private val worker: Executor,
    private val main: Executor,
    private val glassesIp: () -> String = { "" },
    private val now: () -> Instant = Instant::now,
    initialVoice: Boolean = false,
    private val mic: VoiceMic = NoVoiceMic,
    private val announcer: Announcer = NoAnnouncer,
    private val voiceConfig: VoiceConfig = VoiceConfig(),
) {
    private sealed interface CapturePhase {
        data object Idle : CapturePhase
        data object Starting : CapturePhase
        data class Recording(val id: String, val startedAt: Instant, val hasAudio: Boolean) : CapturePhase
        data object Saving : CapturePhase
    }

    private sealed interface ScanPhase {
        data object Ready : ScanPhase
        data object Working : ScanPhase
        data class ShowCard(
            val card: Card,
            val cursor: SpokenCursor,
            val expanded: Boolean = false,
        ) : ScanPhase
        data class Message(val text: String) : ScanPhase
    }

    private var mode = Mode.HOME
    private val voice = initialVoice
    private var phoneLinked: Boolean? = null
    private var homeNote = ""
    private var pairingState: PairingState? = null
    private var pairingIndex = 0
    private var capture: CapturePhase = CapturePhase.Idle
    private var captureNote = ""
    private var stopWhenStarted = false
    private var scan: ScanPhase = ScanPhase.Ready
    private var scanGeneration = 0

    /** Set while the current capture was started by a greeting rather than a click. */
    private class HandsFree(val startedMs: Long, val tapped: Boolean) {
        @Volatile
        var timedOut = false
        var needsFace = false
        var faceFound = false
        var faceBusy = false
        var faceTries = 0
        var nextFaceMs = 0L
        var card: Card? = null
        var personId: String? = null
        var expanded = false
    }

    private var handsFree: HandsFree? = null
    private var foreground = false
    private var worn = true
    private val session = EventSession(voiceConfig)
    private val micEvents = object : VoiceEvents {
        override fun onPhrase(text: String) = onHeard(text)
        override fun onSpeech() = session.onSpeech(nowMs())
    }

    private val voiceActive get() = voice && speaker.available

    val isRecording get() = capture is CapturePhase.Recording

    fun refresh() = render()

    /** The app is on screen, so it listens for a greeting. */
    fun onForeground() {
        foreground = true
        syncListening()
        render()
    }

    /** The speech model finished loading, or a permission changed. */
    fun onVoiceReady() {
        syncListening()
        render()
    }

    /** The result of the regular check that the phone can be reached. */
    fun onPhoneLink(linked: Boolean) {
        if (phoneLinked == linked) return
        phoneLinked = linked
        render()
    }

    /** Glasses that are off the face must not listen, and a hands-free recording ends. */
    fun onWornChanged(isWorn: Boolean) {
        if (worn == isWorn) return
        worn = isWorn
        if (!isWorn && handsFree != null) {
            when (capture) {
                is CapturePhase.Recording -> stopCapture()
                CapturePhase.Starting -> stopWhenStarted = true
                else -> Unit
            }
        }
        syncListening()
        render()
    }

    /** Called once a second. */
    fun onTick() {
        handsFree?.let { active ->
            val waited = nowMs() - active.startedMs
            if (capture == CapturePhase.Starting && !active.tapped && !active.timedOut &&
                waited >= voiceConfig.confirmTimeoutMs
            ) {
                // No face confirmed in time, so stop saving now rather than when the check ends.
                active.timedOut = true
                runCatching { mic.stopFile() }
            }
            retryFace(active)
        }
        if (session.onTick(nowMs()) == EventStep.Stop) {
            mic.clearHeard()
            stopCapture()
        }
        // Another app may have taken the mic for a moment, so listening recovers by itself.
        if (foreground && worn && capture == CapturePhase.Idle && mic.ready && !mic.listening) {
            syncListening()
            render()
        }
        if (isRecording) render()
    }

    private fun nowMs() = now().toEpochMilli()

    /** A click recording uses MediaRecorder, which needs the mic to itself. */
    private fun syncListening() {
        val clickCapture = capture != CapturePhase.Idle && handsFree == null
        if (handsFree != null || (foreground && worn && !clickCapture && mic.ready)) {
            mic.startListening(micEvents)
        } else {
            mic.stopListening()
        }
    }

    private fun onHeard(text: String) {
        if (pairingState != null) return
        if (handsFree == null && (capture != CapturePhase.Idle || scan == ScanPhase.Working)) return
        when (session.onPhrase(text, nowMs())) {
            EventStep.StartCapture -> {
                mic.clearHeard()
                startHandsFree()
            }
            EventStep.Stop -> {
                mic.clearHeard()
                stopCapture()
            }
            EventStep.ClearHeard -> mic.clearHeard()
            null -> Unit
        }
    }

    /**
     * Audio starts at once, because the first words are usually the person's name. If the camera
     * finds no face, that audio is deleted and the app goes back to listening.
     */
    private fun startHandsFree(tapped: Boolean = false) {
        val active = HandsFree(nowMs(), tapped)
        val startedAt = now()
        handsFree = active
        homeNote = ""
        capture = CapturePhase.Starting
        stopWhenStarted = false
        scanGeneration++
        speaker.stop()
        cues.recordStart()
        if (tapped) announcer.started()
        syncListening()
        render()
        worker.execute {
            var id: String? = null
            var result: RecognizeResult? = null
            try {
                val newId = store.newCapture(startedAt).also { id = it }
                mic.startFile(store.audioFile(newId))
                val photos = camera.takeStills(voiceConfig.photos)
                val checked = recognizeAny(photos)
                if (keepsRecording(checked, active.tapped)) store.savePhotos(newId, photos)
                result = checked
            } catch (e: Exception) {
                result = null
            }
            val kept = result?.let { keepsRecording(it, active.tapped) } == true
            if (!kept) {
                runCatching { mic.stopFile() }
                id?.let { runCatching { store.discard(it) } }
            }
            val finalId = id
            val finalResult = result
            main.execute {
                if (kept && !active.timedOut && finalId != null && finalResult != null) {
                    onHandsFreeRecording(active, finalId, startedAt, finalResult)
                } else {
                    if (kept) {
                        worker.execute {
                            runCatching { mic.stopFile() }
                            finalId?.let { runCatching { store.discard(it) } }
                        }
                    }
                    onHandsFreeCancelled(active)
                }
            }
        }
    }

    /**
     * A tap is the wearer's own decision to record. A voice trigger may be someone else's
     * conversation, so it needs a confirmed face in front of the wearer.
     */
    private fun keepsRecording(result: RecognizeResult, tapped: Boolean): Boolean = tapped ||
        when (result) {
            // The phone's stage backup card has score 0, because no face was actually checked.
            is RecognizeResult.Match -> result.score > 0
            is RecognizeResult.Failure -> result.reason == "no_match"
        }

    private fun onHandsFreeRecording(
        active: HandsFree,
        id: String,
        startedAt: Instant,
        result: RecognizeResult,
    ) {
        capture = CapturePhase.Recording(id, startedAt, hasAudio = true)
        session.onRecording(nowMs())
        if (result is RecognizeResult.Match) {
            active.card = result.card
            active.personId = result.capturePersonId
            cues.match()
        }
        if (active.tapped && !hasConfirmedFace(result)) {
            active.needsFace = true
            active.nextFaceMs = nowMs() + voiceConfig.faceRetryMs
        } else {
            active.faceFound = true
        }
        if (!active.tapped) announcer.started()
        render()
        if (stopWhenStarted) stopCapture()
    }

    private fun hasConfirmedFace(result: RecognizeResult): Boolean = when (result) {
        is RecognizeResult.Match -> result.score > 0
        is RecognizeResult.Failure -> result.reason == "no_match"
    }

    /** Tries the newest photo first, because the camera's exposure has settled by then. */
    private fun recognizeAny(photos: List<ByteArray>): RecognizeResult {
        var last: RecognizeResult = RecognizeResult.Failure("no_face")
        for (photo in photos.asReversed()) {
            last = phone.recognize(photo)
            if (hasConfirmedFace(last)) return last
            if (last is RecognizeResult.Failure && last.reason !in FACE_MISSES) return last
        }
        return last
    }

    /** The wearer may tap before facing the person, so keep looking while it records. */
    private fun retryFace(active: HandsFree) {
        val recording = capture as? CapturePhase.Recording ?: return
        if (!active.needsFace || active.faceBusy || nowMs() < active.nextFaceMs) return
        if (active.faceTries >= voiceConfig.faceRetries) {
            active.needsFace = false
            render()
            return
        }
        active.faceBusy = true
        active.faceTries++
        worker.execute {
            val checked = runCatching {
                val photos = camera.takeStills(voiceConfig.photos)
                recognizeAny(photos).also {
                    if (hasConfirmedFace(it)) store.savePhotos(recording.id, photos)
                }
            }.getOrNull()
            main.execute { onFaceRetried(active, checked) }
        }
    }

    private fun onFaceRetried(active: HandsFree, result: RecognizeResult?) {
        active.faceBusy = false
        active.nextFaceMs = nowMs() + voiceConfig.faceRetryMs
        if (handsFree !== active || result == null || !hasConfirmedFace(result)) return
        active.needsFace = false
        active.faceFound = true
        if (result is RecognizeResult.Match) {
            active.card = result.card
            active.personId = result.capturePersonId
        }
        cues.match()
        render()
    }

    private fun onHandsFreeCancelled(active: HandsFree) {
        capture = CapturePhase.Idle
        handsFree = null
        stopWhenStarted = false
        session.onNoFace(nowMs())
        cues.noMatch()
        if (active.tapped) {
            // "Recording started" was already said, so people must hear that it ended.
            announcer.finished()
            homeNote = messageFor(REASON_CAMERA_FAILED)
        }
        syncListening()
        render()
    }

    private fun stopHandsFree(recording: CapturePhase.Recording, active: HandsFree) {
        stopWhenStarted = false
        session.onStopping()
        capture = CapturePhase.Saving
        render()
        worker.execute {
            val note = try {
                val audioSaved = runCatching { mic.stopFile() }.getOrDefault(false)
                if (!audioSaved) store.removeAudio(recording.id)
                store.finish(recording.id, recording.startedAt, now(), active.personId)
                if (audioSaved) "Saved" else "Saved, NO AUDIO"
            } catch (_: Exception) {
                "Save failed"
            }
            main.execute {
                capture = CapturePhase.Idle
                handsFree = null
                captureNote = note
                homeNote = if (note == "Saved" && !active.faceFound) "Saved, no face found" else note
                // Back to the ready screen by itself, so the next person needs no reset.
                mode = Mode.HOME
                scan = ScanPhase.Ready
                session.onFinished(nowMs())
                cues.recordStop()
                announcer.finished()
                syncListening()
                render()
            }
            if (note != "Save failed") runCatching { phone.captureReady() }
        }
    }

    fun openPairing() {
        if (capture != CapturePhase.Idle) return
        mode = Mode.HOME
        pairingIndex = 0
        pairingState = PairingState("Starting Bluetooth")
        pairing.open { state ->
            if (pairingState != null) {
                pairingState = state
                pairingIndex = pairingIndex.coerceIn(0, (state.devices.size - 1).coerceAtLeast(0))
                render()
            }
        }
        render()
    }

    fun onInput(action: InputAction) {
        when (action) {
            is InputAction.Scroll -> onScroll(action.delta)
            InputAction.Primary -> onPrimary()
            InputAction.SwitchMode -> onSwitchMode()
        }
    }

    /** The app left the screen. Save any recording, because the mic and camera must not stay on. */
    fun onBackground() {
        foreground = false
        speaker.stop()
        closePairing()
        when (capture) {
            is CapturePhase.Recording -> stopCapture()
            CapturePhase.Starting -> stopWhenStarted = true
            else -> Unit
        }
        syncListening()
    }

    private fun onScroll(delta: Int) {
        val pairingNow = pairingState
        val active = handsFree
        when {
            active != null -> {
                val card = active.card ?: return
                if (card.glance != null && !active.expanded) {
                    if (delta > 0) {
                        active.expanded = true
                        render()
                    }
                } else {
                    ui.scrollBody(delta)
                }
            }
            pairingNow != null -> {
                val last = (pairingNow.devices.size - 1).coerceAtLeast(0)
                pairingIndex = (pairingIndex + delta).coerceIn(0, last)
                render()
            }
            mode == Mode.RECOGNIZE -> scrollCard(delta)
        }
    }

    private fun scrollCard(delta: Int) {
        val shown = scan as? ScanPhase.ShowCard ?: return
        if (!voiceActive) {
            if (shown.card.glance != null && !shown.expanded) {
                if (delta > 0) {
                    scan = shown.copy(expanded = true)
                    render()
                }
                return
            }
            ui.scrollBody(delta)
            return
        }
        if (!shown.cursor.move(delta)) {
            speaker.stop()
            scan = ScanPhase.Ready
        } else {
            shown.cursor.current()?.let { speaker.say(listOf(it)) }
        }
        render()
    }

    private fun onPrimary() {
        val pairingNow = pairingState
        when {
            handsFree != null -> when (capture) {
                is CapturePhase.Recording -> stopCapture()
                CapturePhase.Starting -> stopWhenStarted = true
                else -> Unit
            }
            pairingNow != null -> pairingNow.devices.getOrNull(pairingIndex)?.let { pairing.pair(it.address) }
            mode == Mode.HOME -> onHomeTap()
            mode == Mode.CAPTURE -> when (capture) {
                // Same flow as Home, so a stray mode change cannot skip the face check.
                CapturePhase.Idle -> if (mic.ready) onHomeTap() else startCapture()
                is CapturePhase.Recording -> stopCapture()
                else -> Unit
            }
            mode == Mode.RECOGNIZE -> onRecognizePrimary()
        }
    }

    /** One tap starts a recording from Home, and the next tap ends it. */
    private fun onHomeTap() {
        if (capture != CapturePhase.Idle) return
        if (!mic.ready) {
            mode = Mode.CAPTURE
            startCapture()
        } else if (session.onTapStart()) {
            mic.clearHeard()
            startHandsFree(tapped = true)
        }
    }

    private fun onRecognizePrimary() {
        val current = scan
        when {
            current == ScanPhase.Working -> Unit
            current is ScanPhase.ShowCard && voiceActive ->
                current.cursor.current()?.let { speaker.say(listOf(it)) }
            else -> startScan()
        }
    }

    private fun onSwitchMode() {
        if (pairingState != null) {
            closePairing()
            render()
            return
        }
        if (capture != CapturePhase.Idle) {
            captureNote = "Click to stop first"
            render()
            return
        }
        speaker.stop()
        scanGeneration++
        scan = ScanPhase.Ready
        captureNote = ""
        mode = mode.next()
        render()
    }

    private fun closePairing() {
        if (pairingState != null) {
            pairingState = null
            pairing.close()
        }
    }

    private fun startCapture() {
        capture = CapturePhase.Starting
        captureNote = ""
        stopWhenStarted = false
        syncListening()
        render()
        val startedAt = now()
        worker.execute {
            val result = runCatching {
                val id = store.newCapture(startedAt)
                // Audio starts first, so the opening of the conversation is not lost to the camera.
                val hasAudio = runCatching { audio.start(store.audioFile(id)); true }.getOrDefault(false)
                try {
                    store.savePhotos(id, camera.takeStills(CAPTURE_PHOTOS))
                } catch (e: Exception) {
                    if (hasAudio) runCatching { audio.stop() }
                    store.discard(id)
                    throw e
                }
                CapturePhase.Recording(id, startedAt, hasAudio)
            }
            main.execute { onCaptureStarted(result) }
        }
    }

    private fun onCaptureStarted(result: Result<CapturePhase.Recording>) {
        val recording = result.getOrNull()
        if (recording == null) {
            capture = CapturePhase.Idle
            captureNote = messageFor(REASON_CAMERA_FAILED)
            if (voiceActive) speaker.say(listOf(0 to captureNote))
            syncListening()
            render()
            return
        }
        capture = recording
        captureNote = if (recording.hasAudio) "" else "NO AUDIO"
        // Always audible: the M6 has no recording light, so people nearby must be able to tell.
        cues.recordStart()
        announcer.started()
        render()
        if (stopWhenStarted) stopCapture()
    }

    private fun stopCapture() {
        val recording = capture as? CapturePhase.Recording ?: return
        handsFree?.let {
            stopHandsFree(recording, it)
            return
        }
        stopWhenStarted = false
        capture = CapturePhase.Saving
        render()
        worker.execute {
            val note = try {
                val audioSaved = recording.hasAudio && runCatching { audio.stop() }.getOrDefault(false)
                if (!audioSaved) store.removeAudio(recording.id)
                store.finish(recording.id, recording.startedAt, now())
                if (audioSaved) "Saved" else "Saved, NO AUDIO"
            } catch (_: Exception) {
                "Save failed"
            }
            main.execute {
                capture = CapturePhase.Idle
                captureNote = note
                cues.recordStop()
                announcer.finished()
                syncListening()
                render()
            }
            if (note != "Save failed") runCatching { phone.captureReady() }
        }
    }

    private fun startScan() {
        val generation = ++scanGeneration
        scan = ScanPhase.Working
        speaker.stop()
        render()
        worker.execute {
            val result = try {
                phone.recognize(camera.takeStills(1).first())
            } catch (_: Exception) {
                RecognizeResult.Failure(REASON_CAMERA_FAILED)
            }
            main.execute { if (generation == scanGeneration) showResult(result) }
        }
    }

    private fun showResult(result: RecognizeResult) {
        when (result) {
            is RecognizeResult.Match -> {
                val cursor = SpokenCursor(result.card.spokenItems)
                scan = ScanPhase.ShowCard(result.card, cursor)
                if (voiceActive) {
                    cues.match()
                    speaker.say(cursor.autoItems()) { index ->
                        cursor.index = index
                        render()
                    }
                }
            }
            is RecognizeResult.Failure -> {
                val text = messageFor(result.reason)
                scan = ScanPhase.Message(text)
                if (voiceActive) {
                    cues.noMatch()
                    speaker.say(listOf(0 to text))
                }
            }
        }
        render()
    }

    private fun render() {
        val state = pairingState
        val active = handsFree
        val screen = when {
            active != null -> handsFreeScreen(active)
            state != null -> pairingScreen(state)
            mode == Mode.HOME -> homeScreen()
            mode == Mode.CAPTURE -> captureScreen()
            else -> recognizeScreen()
        }
        val status = when {
            screen.status.isNotEmpty() -> screen.status
            mic.listening -> "● Listening"
            !worn && mic.ready -> "Not worn"
            else -> ""
        }
        ui.render(screen.copy(status = status))
    }

    private fun handsFreeScreen(active: HandsFree): Screen {
        val phase = capture as? CapturePhase.Recording
            ?: return Screen(
                title = "Recall",
                status = "REC",
                body = if (capture == CapturePhase.Saving) "Saving" else "Looking...",
                recording = true,
            )
        val seconds = Duration.between(phase.startedAt, now()).seconds.coerceAtLeast(0)
        val status = "REC %02d:%02d".format(seconds / 60, seconds % 60)
        val card = active.card
        val endHint = "Tap or say \"${voiceConfig.endPrompt}\""
        return when {
            card == null && active.needsFace -> Screen(
                title = "Look at their face",
                status = status,
                hint = endHint,
                crosshair = true,
                recording = true,
            )
            card == null -> Screen(
                title = "New person",
                status = status,
                body = "Recording this\nconversation",
                hint = endHint,
                recording = true,
            )
            card.glance != null && !active.expanded -> Screen(
                title = card.glance.name,
                status = status,
                body = card.glance.line,
                hint = endHint,
                recording = true,
            )
            else -> Screen(
                title = card.name,
                status = status,
                body = card.displayText(),
                hint = endHint,
                recording = true,
            )
        }
    }

    private fun homeScreen(): Screen {
        val message = when {
            !mic.ready -> "Tap to record"
            !worn -> "Put on the glasses\nor tap to record"
            phoneLinked == false -> "Phone not connected\nTap still records"
            else -> "Say\n\"${voiceConfig.startPrompt}\"\nor tap to record"
        }
        val waiting = store.list().size
        val footer = buildList {
            add(
                when (phoneLinked) {
                    true -> "Phone connected"
                    false -> "IP: ${glassesIp().ifBlank { "no Wi-Fi" }}"
                    null -> "Finding phone"
                },
            )
            if (waiting > 0) add("$waiting to sync")
        }
        return Screen(
            title = Mode.HOME.title,
            body = listOf(homeNote, message).filter { it.isNotEmpty() }.joinToString("\n"),
            hint = footer.joinToString("   "),
        )
    }

    private fun pairingScreen(state: PairingState): Screen {
        val first = (pairingIndex - PAIRING_ROWS + 1).coerceAtLeast(0)
        val rows = state.devices.drop(first).take(PAIRING_ROWS).mapIndexed { offset, device ->
            val marker = if (first + offset == pairingIndex) "> " else "  "
            marker + device.name + if (device.bonded) " *" else ""
        }
        return Screen(
            title = "Pair mouse",
            body = (listOf(state.status) + rows).joinToString("\n"),
            hint = "Click: pair  Right: back",
        )
    }

    private fun captureScreen(): Screen = when (val phase = capture) {
        CapturePhase.Idle -> Screen(
            title = Mode.CAPTURE.title,
            body = captureNote,
            hint = "Tap: record  Hold: mode",
            crosshair = captureNote.isEmpty(),
        )
        CapturePhase.Starting -> Screen(Mode.CAPTURE.title, body = "Taking photos", crosshair = true)
        is CapturePhase.Recording -> {
            val seconds = Duration.between(phase.startedAt, now()).seconds.coerceAtLeast(0)
            Screen(
                title = Mode.CAPTURE.title,
                status = "REC %02d:%02d".format(seconds / 60, seconds % 60),
                body = captureNote,
                hint = "Tap: stop",
                recording = true,
            )
        }
        CapturePhase.Saving -> Screen(Mode.CAPTURE.title, body = "Saving")
    }

    private fun recognizeScreen(): Screen = when (val phase = scan) {
        ScanPhase.Ready -> Screen(
            title = Mode.RECOGNIZE.title,
            hint = "Tap: scan  Hold: mode",
            crosshair = true,
        )
        ScanPhase.Working -> Screen(Mode.RECOGNIZE.title, body = "Looking...")
        is ScanPhase.Message -> Screen(
            title = Mode.RECOGNIZE.title,
            body = phase.text,
            hint = "Tap: scan again",
        )
        is ScanPhase.ShowCard -> if (voiceActive) {
            Screen(
                title = phase.card.name,
                status = "${phase.cursor.index + 1}/${phase.cursor.items.size}",
                body = phase.cursor.current()?.second.orEmpty(),
                hint = "Scroll: next  Tap: repeat",
            )
        } else if (phase.card.glance != null && !phase.expanded) {
            Screen(
                title = phase.card.glance.name,
                body = phase.card.glance.line,
                hint = "Scroll: more  Tap: scan",
            )
        } else {
            Screen(
                title = Mode.RECOGNIZE.title,
                body = phase.card.displayText(),
                hint = "Scroll: read  Tap: scan",
            )
        }
    }

    private companion object {
        const val CAPTURE_PHOTOS = 3
        const val PAIRING_ROWS = 4
        val FACE_MISSES = setOf("no_face", "multiple_faces")
    }
}
