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
    private val saveVoice: (Boolean) -> Unit = {},
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
    private var voice = initialVoice
    private var menuIndex = 0
    private var pairingState: PairingState? = null
    private var pairingIndex = 0
    private var capture: CapturePhase = CapturePhase.Idle
    private var captureNote = ""
    private var stopWhenStarted = false
    private var scan: ScanPhase = ScanPhase.Ready
    private var scanGeneration = 0

    private val voiceActive get() = voice && speaker.available

    val isRecording get() = capture is CapturePhase.Recording

    fun refresh() = render()

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
        speaker.stop()
        closePairing()
        when (capture) {
            is CapturePhase.Recording -> stopCapture()
            CapturePhase.Starting -> stopWhenStarted = true
            else -> Unit
        }
    }

    private fun onScroll(delta: Int) {
        val pairingNow = pairingState
        when {
            pairingNow != null -> {
                val last = (pairingNow.devices.size - 1).coerceAtLeast(0)
                pairingIndex = (pairingIndex + delta).coerceIn(0, last)
                render()
            }
            mode == Mode.HOME -> {
                menuIndex = (menuIndex + delta).coerceIn(0, MENU_SIZE - 1)
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
            pairingNow != null -> pairingNow.devices.getOrNull(pairingIndex)?.let { pairing.pair(it.address) }
            mode == Mode.HOME -> activateMenu()
            mode == Mode.CAPTURE -> when (capture) {
                CapturePhase.Idle -> startCapture()
                is CapturePhase.Recording -> stopCapture()
                else -> Unit
            }
            mode == Mode.RECOGNIZE -> onRecognizePrimary()
        }
    }

    private fun activateMenu() {
        if (menuIndex == MENU_VOICE) {
            voice = !voice
            saveVoice(voice)
            if (!voice) speaker.stop()
            render()
        } else {
            openPairing()
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
            render()
            return
        }
        capture = recording
        captureNote = if (recording.hasAudio) "" else "NO AUDIO"
        if (voiceActive) cues.recordStart()
        render()
        if (stopWhenStarted) stopCapture()
    }

    private fun stopCapture() {
        val recording = capture as? CapturePhase.Recording ?: return
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
                if (voiceActive) cues.recordStop()
                render()
            }
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
        ui.render(
            when {
                state != null -> pairingScreen(state)
                mode == Mode.HOME -> homeScreen()
                mode == Mode.CAPTURE -> captureScreen()
                else -> recognizeScreen()
            },
        )
    }

    private fun homeScreen(): Screen {
        val voiceLabel = when {
            voice && !speaker.available -> "Voice: no engine"
            voice -> "Voice: on"
            else -> "Voice: off"
        }
        val mouse = pairing.connectedName()?.let { "Mouse: $it" } ?: "Pair mouse"
        val lines = listOf(voiceLabel, mouse).mapIndexed { index, text ->
            (if (index == menuIndex) "> " else "  ") + text
        } + "Saved: ${store.list().size}" + "IP: ${glassesIp().ifBlank { "no Wi-Fi" }}"
        return Screen(
            title = Mode.HOME.title,
            body = lines.joinToString("\n"),
            hint = "Click: select  Right: mode",
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
            hint = "Click: record  Right: mode",
            crosshair = captureNote.isEmpty(),
        )
        CapturePhase.Starting -> Screen(Mode.CAPTURE.title, body = "Taking photos", crosshair = true)
        is CapturePhase.Recording -> {
            val seconds = Duration.between(phase.startedAt, now()).seconds.coerceAtLeast(0)
            Screen(
                title = Mode.CAPTURE.title,
                status = "REC %02d:%02d".format(seconds / 60, seconds % 60),
                body = captureNote,
                hint = "Click: stop",
                recording = true,
            )
        }
        CapturePhase.Saving -> Screen(Mode.CAPTURE.title, body = "Saving")
    }

    private fun recognizeScreen(): Screen = when (val phase = scan) {
        ScanPhase.Ready -> Screen(
            title = Mode.RECOGNIZE.title,
            hint = "Click: scan  Right: mode",
            crosshair = true,
        )
        ScanPhase.Working -> Screen(Mode.RECOGNIZE.title, body = "Looking...")
        is ScanPhase.Message -> Screen(
            title = Mode.RECOGNIZE.title,
            body = phase.text,
            hint = "Click: scan again",
        )
        is ScanPhase.ShowCard -> if (voiceActive) {
            Screen(
                title = phase.card.name,
                status = "${phase.cursor.index + 1}/${phase.cursor.items.size}",
                body = phase.cursor.current()?.second.orEmpty(),
                hint = "Scroll: next  Click: repeat",
            )
        } else if (phase.card.glance != null && !phase.expanded) {
            Screen(
                title = phase.card.glance.name,
                body = phase.card.glance.line,
                hint = "Scroll: more  Click: scan",
            )
        } else {
            Screen(
                title = Mode.RECOGNIZE.title,
                body = phase.card.displayText(),
                hint = "Scroll: read  Click: scan",
            )
        }
    }

    private companion object {
        const val CAPTURE_PHOTOS = 3
        const val MENU_VOICE = 0
        const val MENU_SIZE = 2
        const val PAIRING_ROWS = 4
    }
}
