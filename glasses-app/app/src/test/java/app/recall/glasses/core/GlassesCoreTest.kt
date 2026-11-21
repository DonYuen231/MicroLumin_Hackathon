package app.recall.glasses.core

import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val T0: Instant = Instant.parse("2026-11-21T15:15:03Z")
private val T1: Instant = Instant.parse("2026-11-21T15:18:10Z")

private val MATCH_JSON = """
{"match":true,"person_id":"p_1","score":0.62,"card":{"name":"Jordan Lee",
"headline":"Hardware engineer","display_items":[{"label":"Talk about","text":"Gripper"}],
"spoken_items":["Jordan Lee, engineer.","Avoid: the layoffs.","Send the contact."]}}
""".trimIndent()

class GlassesCoreTest {
    private lateinit var root: File
    private lateinit var store: CaptureStore

    @Before
    fun setUp() {
        root = Files.createTempDirectory("captures").toFile()
        store = CaptureStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun finishedCapture(photos: Int = 3, audio: Boolean = true): String {
        val id = store.newCapture(T0)
        store.savePhotos(id, List(photos) { byteArrayOf(1, 2, it.toByte()) })
        if (audio) store.audioFile(id).writeBytes(byteArrayOf(9, 9))
        store.finish(id, T0, T1)
        return id
    }

    @Test
    fun captureIdAndTimesFollowTheContract() {
        val id = finishedCapture()
        assertEquals("c_20261121_151503", id)
        val info = store.list().single()
        assertEquals("2026-11-21T15:15:03Z", info.startedAt)
        assertEquals("2026-11-21T15:18:10Z", info.endedAt)
        assertEquals(3, info.photos)
        assertTrue(info.hasAudio)
    }

    @Test
    fun captureStillRecordingIsHiddenAndCannotBeDeleted() {
        val id = store.newCapture(T0)
        store.savePhotos(id, listOf(byteArrayOf(1)))
        assertTrue(store.list().isEmpty())
        assertNull(store.photo(id, 0))
        assertFalse(store.delete(id))
        assertTrue(File(root, id).exists())
    }

    @Test
    fun sameSecondCapturesGetUniqueIds() {
        assertEquals("c_20261121_151503", store.newCapture(T0))
        assertEquals("c_20261121_151503_2", store.newCapture(T0))
    }

    @Test
    fun recoverKeepsPhotosButDropsUnclosedAudio() {
        val id = store.newCapture(T0)
        store.savePhotos(id, listOf(byteArrayOf(1)))
        store.audioFile(id).writeBytes(byteArrayOf(9))
        val empty = store.newCapture(T1)
        store.recover(T1)
        val info = store.list().single()
        assertEquals(id, info.id)
        assertEquals(1, info.photos)
        assertFalse(info.hasAudio)
        assertFalse(File(root, empty).exists())
    }

    @Test
    fun serverFollowsThePhoneContract() = withServer({ true }) { base ->
        val id = finishedCapture()
        val list = JSONObject(request("GET", "$base/captures").second)
        val capture = list.getJSONArray("captures").getJSONObject(0)
        assertEquals(id, capture.getString("capture_id"))
        assertEquals(3, capture.getInt("photos"))
        assertTrue(capture.getBoolean("has_audio"))

        assertEquals(200, request("GET", "$base/captures/$id/photos/2").first)
        assertEquals(404, request("GET", "$base/captures/$id/photos/3").first)
        assertEquals(200, request("GET", "$base/captures/$id/audio").first)
        assertEquals(404, request("GET", "$base/captures/..%2Fsecret/audio").first)

        assertEquals(200, request("DELETE", "$base/captures/$id").first)
        assertFalse(File(root, id).exists())
        assertEquals(404, request("DELETE", "$base/captures/$id").first)
    }

    @Test
    fun serverBlocksAnyoneButThePhone() = withServer({ false }) { base ->
        val id = finishedCapture()
        assertEquals(403, request("GET", "$base/captures").first)
        assertEquals(403, request("DELETE", "$base/captures/$id").first)
        assertTrue(File(root, id).exists())
    }

    @Test
    fun parsesCardsAndFailures() {
        val match = parseRecognize(MATCH_JSON) as RecognizeResult.Match
        assertEquals("Jordan Lee", match.card.name)
        assertEquals(3, match.card.spokenItems.size)
        assertEquals(
            RecognizeResult.Failure("untrusted_device"),
            parseRecognize("""{"ok":false,"reason":"untrusted_device"}"""),
        )
        assertEquals(RecognizeResult.Failure("unknown"), parseRecognize("not json"))
        assertEquals("Trust glasses on phone", messageFor("untrusted_device"))
    }

    @Test
    fun convertsLittleEndianGateway() {
        assertEquals("192.168.43.1", dhcpIntToIp(0x012BA8C0))
        assertNull(dhcpIntToIp(0))
    }

    @Test
    fun gesturesMapToActions() {
        val tracker = GestureTracker()
        tracker.down(10f, 10f, 0)
        assertEquals(InputAction.Primary, tracker.up(12f, 11f, 100))
        tracker.down(10f, 10f, 0)
        assertEquals(InputAction.SwitchMode, tracker.up(10f, 10f, 700))
        tracker.down(10f, 200f, 0)
        assertEquals(InputAction.Scroll(1), tracker.up(10f, 100f, 100))
        tracker.down(200f, 10f, 0)
        assertEquals(InputAction.Scroll(-1), tracker.up(100f, 10f, 100))
    }

    @Test
    fun oneMouseClickCountsOnce() {
        val debouncer = ClickDebouncer()
        assertTrue(debouncer.accept(InputAction.SwitchMode, 1000))
        assertFalse(debouncer.accept(InputAction.SwitchMode, 1010))
        assertTrue(debouncer.accept(InputAction.Primary, 1010))
        assertTrue(debouncer.accept(InputAction.SwitchMode, 1300))
    }

    @Test
    fun ranksMiceAndHidesOtherDevices() {
        val mouseClass = 0x2580
        val phoneClass = 0x5A020C
        assertTrue(MouseRanking.isPointing(mouseClass))
        assertFalse(MouseRanking.isCandidate(phoneClass, "Someone's phone"))
        assertTrue(MouseRanking.isCandidate(0, "BLE Mouse"))
        assertFalse(MouseRanking.isCandidate(0, null))
        val sorted = MouseRanking.sort(
            listOf(
                MouseCandidate("A", "Unknown LE", bonded = false, pointing = false),
                MouseCandidate("B", "Real Mouse", bonded = false, pointing = true),
            ),
        )
        assertEquals("B", sorted.first().address)
    }

    @Test
    fun rightClickCyclesModes() {
        val rig = Rig()
        assertEquals("Recall", rig.ui.last.title)
        rig.controller.onInput(InputAction.SwitchMode)
        assertEquals("Capture", rig.ui.last.title)
        rig.controller.onInput(InputAction.SwitchMode)
        assertEquals("Recognize", rig.ui.last.title)
        rig.controller.onInput(InputAction.SwitchMode)
        assertEquals("Recall", rig.ui.last.title)
    }

    @Test
    fun captureFlowSavesThreePhotosAndAudio() {
        val rig = Rig()
        rig.controller.onInput(InputAction.SwitchMode)
        rig.controller.onInput(InputAction.Primary)
        assertTrue(rig.ui.last.recording)
        assertTrue("A recording must not be listed", store.list().isEmpty())

        rig.controller.onInput(InputAction.SwitchMode)
        assertEquals("Click to stop first", rig.ui.last.body)
        assertEquals("Capture", rig.ui.last.title)

        rig.controller.onInput(InputAction.Primary)
        val saved = store.list().single()
        assertEquals(3, saved.photos)
        assertTrue(saved.hasAudio)
    }

    @Test
    fun audioFailureStillSavesPhotosAndWarns() {
        val rig = Rig(audio = FakeAudio(failStart = true))
        rig.controller.onInput(InputAction.SwitchMode)
        rig.controller.onInput(InputAction.Primary)
        assertEquals("NO AUDIO", rig.ui.last.body)
        rig.controller.onInput(InputAction.Primary)
        assertEquals("Saved, NO AUDIO", rig.ui.last.body)
        assertFalse(store.list().single().hasAudio)
    }

    @Test
    fun cameraFailureLeavesNoCapture() {
        val rig = Rig(camera = FakeCamera(fail = true))
        rig.controller.onInput(InputAction.SwitchMode)
        rig.controller.onInput(InputAction.Primary)
        assertEquals("Camera failed, try again", rig.ui.last.body)
        assertTrue(root.list().orEmpty().none { it.startsWith("c_") })
    }

    @Test
    fun leavingTheAppSavesTheRecording() {
        val rig = Rig()
        rig.controller.onInput(InputAction.SwitchMode)
        rig.controller.onInput(InputAction.Primary)
        rig.controller.onBackground()
        assertEquals(1, store.list().size)
    }

    @Test
    fun voiceSkipsAvoidUntilScrolledTo() {
        val rig = Rig(voice = true, result = parseRecognize(MATCH_JSON))
        rig.scan()
        assertEquals(listOf(0, 2), rig.speaker.spoken.last().map { it.first })
        assertEquals(1, rig.cues.matches)

        rig.controller.onInput(InputAction.Scroll(1))
        assertEquals("Avoid: the layoffs.", rig.speaker.spoken.last().single().second)
        rig.controller.onInput(InputAction.Primary)
        assertEquals("Avoid: the layoffs.", rig.speaker.spoken.last().single().second)

        rig.controller.onInput(InputAction.Scroll(1))
        rig.controller.onInput(InputAction.Scroll(1))
        assertTrue("Scrolling past the end returns to aiming", rig.ui.last.crosshair)
    }

    @Test
    fun displayModeScrollsCardWithoutSpeaking() {
        val rig = Rig(result = parseRecognize(MATCH_JSON))
        rig.scan()
        assertTrue(rig.ui.last.body.startsWith("Jordan Lee"))
        rig.controller.onInput(InputAction.Scroll(1))
        assertEquals(1, rig.ui.scrolled)
        assertTrue(rig.speaker.spoken.isEmpty())
    }

    @Test
    fun lateScanResultIsIgnoredAfterSwitchingModes() {
        val deferred = ArrayDeque<Runnable>()
        val rig = Rig(result = parseRecognize(MATCH_JSON), worker = Executor { deferred.add(it) })
        rig.controller.onInput(InputAction.SwitchMode)
        rig.controller.onInput(InputAction.SwitchMode)
        rig.controller.onInput(InputAction.Primary)
        rig.controller.onInput(InputAction.SwitchMode)
        deferred.removeFirst().run()
        assertEquals("Recall", rig.ui.last.title)
    }

    @Test
    fun pairingListsMiceAndPairsTheSelectedOne() {
        val rig = Rig()
        rig.controller.onInput(InputAction.Scroll(1))
        rig.controller.onInput(InputAction.Primary)
        assertEquals("Pair mouse", rig.ui.last.title)

        rig.pairing.publish(
            PairingState(
                "Pick your mouse",
                listOf(
                    MouseCandidate("AA", "Mouse A", bonded = false, pointing = true),
                    MouseCandidate("BB", "Mouse B", bonded = false, pointing = true),
                ),
            ),
        )
        assertTrue(rig.ui.last.body.contains("> Mouse A"))
        rig.controller.onInput(InputAction.Scroll(1))
        assertTrue(rig.ui.last.body.contains("> Mouse B"))
        rig.controller.onInput(InputAction.Primary)
        assertEquals("BB", rig.pairing.paired)

        rig.controller.onInput(InputAction.SwitchMode)
        assertEquals("Recall", rig.ui.last.title)
        assertTrue(rig.pairing.closed)
    }

    private fun withServer(allowed: (String) -> Boolean, block: (String) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val server = CaptureServer(store, port, allowed)
        server.startServer()
        try {
            block("http://127.0.0.1:$port")
        } finally {
            server.stop()
        }
    }

    private fun request(method: String, url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        return status to stream?.use { it.readBytes().decodeToString() }.orEmpty()
    }

    private inner class Rig(
        camera: CameraSource = FakeCamera(),
        audio: AudioSource = FakeAudio(),
        voice: Boolean = false,
        result: RecognizeResult = RecognizeResult.Failure("no_match"),
        worker: Executor = Executor { it.run() },
    ) {
        val ui = FakeUi()
        val speaker = FakeSpeaker()
        val cues = FakeCues()
        val pairing = FakePairing()
        val controller = AppController(
            camera = camera,
            audio = audio,
            store = store,
            phone = object : PhoneApi {
                override fun recognize(jpeg: ByteArray) = result
            },
            speaker = speaker,
            cues = cues,
            pairing = pairing,
            ui = ui,
            worker = worker,
            main = Executor { it.run() },
            now = { T0 },
            initialVoice = voice,
        ).also { it.refresh() }

        fun scan() {
            controller.onInput(InputAction.SwitchMode)
            controller.onInput(InputAction.SwitchMode)
            controller.onInput(InputAction.Primary)
        }
    }

    private class FakeCamera(private val fail: Boolean = false) : CameraSource {
        override fun takeStills(count: Int): List<ByteArray> {
            check(!fail) { "No camera" }
            return List(count) { byteArrayOf(1, it.toByte()) }
        }
    }

    private class FakeAudio(private val failStart: Boolean = false) : AudioSource {
        override fun start(file: File) {
            check(!failStart) { "No mic" }
            file.writeBytes(byteArrayOf(7))
        }

        override fun stop() = true
    }

    private class FakeUi : Ui {
        lateinit var last: Screen
        var scrolled = 0

        override fun render(screen: Screen) {
            last = screen
        }

        override fun scrollBody(delta: Int) {
            scrolled += delta
        }
    }

    private class FakeSpeaker : Speaker {
        override val available = true
        val spoken = mutableListOf<List<Pair<Int, String>>>()

        override fun say(items: List<Pair<Int, String>>, onItem: (Int) -> Unit) {
            spoken += items
        }

        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private class FakeCues : Cues {
        var matches = 0
        override fun recordStart() = Unit
        override fun recordStop() = Unit
        override fun match() {
            matches++
        }

        override fun noMatch() = Unit
    }

    private class FakePairing : MousePairing {
        private var listener: ((PairingState) -> Unit)? = null
        var paired: String? = null
        var closed = false

        override fun open(listener: (PairingState) -> Unit) {
            this.listener = listener
        }

        fun publish(state: PairingState) = listener?.invoke(state)

        override fun pair(address: String) {
            paired = address
        }

        override fun close() {
            closed = true
        }

        override fun connectedName(): String? = null
    }
}
