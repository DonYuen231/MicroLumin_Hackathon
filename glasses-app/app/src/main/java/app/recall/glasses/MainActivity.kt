package app.recall.glasses

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.recall.glasses.core.AppController
import app.recall.glasses.core.CaptureServer
import app.recall.glasses.core.CaptureStore
import app.recall.glasses.core.Screen
import app.recall.glasses.core.Ui
import app.recall.glasses.core.VoiceConfig
import app.recall.glasses.core.dhcpIntToIp
import app.recall.glasses.device.AndroidSpeaker
import app.recall.glasses.device.BluetoothMouse
import app.recall.glasses.device.Camera1Source
import app.recall.glasses.device.Camera2Source
import app.recall.glasses.device.CardView
import app.recall.glasses.device.ClipAnnouncer
import app.recall.glasses.device.CrosshairView
import app.recall.glasses.device.FallbackCameraSource
import app.recall.glasses.device.GLASS_GREEN
import app.recall.glasses.device.HttpPhoneClient
import app.recall.glasses.device.InputMapper
import app.recall.glasses.device.RecorderAudioSource
import app.recall.glasses.device.ToneCues
import app.recall.glasses.device.VoskMic
import app.recall.glasses.device.WifiJoiner
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class MainActivity : Activity(), Ui {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var cardView: CardView
    private lateinit var crosshair: CrosshairView
    private lateinit var controller: AppController
    private lateinit var input: InputMapper
    private lateinit var speaker: AndroidSpeaker
    private lateinit var mouse: BluetoothMouse
    private lateinit var voiceMic: VoskMic
    private lateinit var announcer: ClipAnnouncer
    private lateinit var phoneClient: HttpPhoneClient
    private val linkChecks = Executors.newSingleThreadScheduledExecutor()
    private var linkTask: ScheduledFuture<*>? = null
    private var server: CaptureServer? = null
    private var phoneOverride: String? = null
    private var wasRecording = false
    private val wifiJoiner by lazy { WifiJoiner(this) }
    private var lastScreen: Screen? = null
    private var onScreen = false
    private var touches = 0
    private var inputs = 0
    private var lastInput = ""
    private var lastKey = ""

    @Volatile
    private var statusJson = "{}"

    private val tick = object : Runnable {
        override fun run() {
            controller.onTick()
            mainHandler.postDelayed(this, 1_000)
        }
    }

    private val dimScreen = Runnable { setBrightness(DIM_BRIGHTNESS) }

    private val wearListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val worn = event.values[0] != 0f
            Log.i("RecallWear", "worn=$worn")
            controller.onWornChanged(worn)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        hideSystemBars()
        readDebugExtras(intent)

        val prefs = getSharedPreferences("recall", MODE_PRIVATE)
        val store = CaptureStore(File(filesDir, "captures"))
        store.recover(Instant.now())

        mouse = BluetoothMouse(this)
        speaker = AndroidSpeaker(this) { if (::controller.isInitialized) controller.refresh() }
        val voiceConfig = VoiceConfig()
        voiceMic = VoskMic(
            modelDir = File(getExternalFilesDir(null), VOICE_MODEL),
            phrases = voiceConfig.allPhrases,
            main = mainHandler,
        ) { if (::controller.isInitialized) controller.onVoiceReady() }
        announcer = ClipAnnouncer(this, mainHandler)
        phoneClient = HttpPhoneClient(::phoneHost)
        controller = AppController(
            camera = FallbackCameraSource(Camera2Source(this), Camera1Source()),
            audio = RecorderAudioSource(),
            store = store,
            phone = phoneClient,
            speaker = speaker,
            cues = ToneCues(),
            pairing = mouse,
            ui = this,
            worker = worker,
            main = { mainHandler.post(it) },
            glassesIp = ::glassesIp,
            initialVoice = prefs.getBoolean(PREF_VOICE, false),
            mic = voiceMic,
            announcer = announcer,
            voiceConfig = voiceConfig,
        )
        voiceMic.load()
        input = InputMapper { action ->
            wake()
            inputs++
            lastInput = action.toString()
            publishStatus()
            controller.onInput(action)
        }

        startServer(store)
        requestMissingPermissions()
        controller.refresh()
        if (intent.getBooleanExtra(EXTRA_PAIR_MOUSE, false)) controller.openPairing()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readDebugExtras(intent)
        if (intent.getBooleanExtra(EXTRA_PAIR_MOUSE, false)) controller.openPairing()
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(tick)
        wake()
        onScreen = true
        controller.onForeground()
        publishStatus()
        val sensors = getSystemService(SensorManager::class.java)
        val wear = sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        wear?.let { sensors.registerListener(wearListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        linkTask = linkChecks.scheduleWithFixedDelay(
            {
                runCatching { wifiJoiner.ensureOnHotspot() }
                val linked = runCatching { phoneClient.isReachable() }.getOrDefault(false)
                mainHandler.post { controller.onPhoneLink(linked) }
            },
            0,
            LINK_CHECK_S,
            TimeUnit.SECONDS,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        controller.onVoiceReady()
    }

    override fun onStop() {
        getSystemService(SensorManager::class.java).unregisterListener(wearListener)
        onScreen = false
        publishStatus()
        linkTask?.cancel(false)
        mainHandler.removeCallbacks(tick)
        mainHandler.removeCallbacks(dimScreen)
        controller.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        server?.stop()
        speaker.shutdown()
        announcer.shutdown()
        linkChecks.shutdownNow()
        // Queued after any recording still being saved, so its audio file is closed first.
        worker.execute { voiceMic.shutdown() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** System bars would float in the wearer's view, because only black pixels are see-through. */
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastKey = KeyEvent.keyCodeToString(event.keyCode)
            publishStatus()
        }
        return input.onKey(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touches++
            publishStatus()
        }
        return input.onTouch(event)
    }

    /** What the phone sees at `/status`. It holds no conversation or card content. */
    private fun publishStatus() {
        statusJson = JSONObject()
            .put("on_screen", onScreen)
            .put("listening", voiceMic.listening)
            .put("recording", lastScreen?.recording == true)
            .put("status", lastScreen?.status.orEmpty())
            .put("touches", touches)
            .put("inputs", inputs)
            .put("last_input", lastInput)
            .put("last_key", lastKey)
            .put("uptime_s", SystemClock.elapsedRealtime() / 1_000)
            .toString()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        input.onGenericMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun render(screen: Screen) {
        // A voice-started recording has no touch to wake the dimmed display.
        if (screen.recording != wasRecording) {
            wasRecording = screen.recording
            wake()
        }
        titleView.text = screen.title
        statusView.text = screen.status
        hintView.text = screen.hint
        cardView.show(screen.body)
        crosshair.visibility = if (screen.crosshair) View.VISIBLE else View.GONE
        lastScreen = screen
        publishStatus()
    }

    override fun scrollBody(delta: Int) = cardView.scrollLines(delta)

    private fun buildLayout(): View {
        titleView = label(26f, bold = true)
        statusView = label(26f, bold = true).apply { gravity = Gravity.END }
        hintView = label(20f)
        cardView = CardView(this)
        crosshair = CrosshairView(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(statusView, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PADDING, PADDING, PADDING, PADDING)
            addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(
                divider(),
                LinearLayout.LayoutParams(MATCH_PARENT, DIVIDER_PX).apply { topMargin = 8 },
            )
            addView(
                cardView,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18 },
            )
            addView(View(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(
                divider(),
                LinearLayout.LayoutParams(MATCH_PARENT, DIVIDER_PX).apply { bottomMargin = 8 },
            )
            addView(hintView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(crosshair, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // A mouse pointer would float in the wearer's view.
            pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        }
    }

    private fun divider() = View(this).apply { setBackgroundColor(Color.rgb(0, 110, 0)) }

    private fun label(size: Float, bold: Boolean = false) = TextView(this).apply {
        setTextColor(GLASS_GREEN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
    }

    private fun startServer(store: CaptureStore) {
        val next = CaptureServer(
            store = store,
            isAllowed = ::isPhone,
            onChanged = { mainHandler.post { controller.refresh() } },
            status = { statusJson },
        )
        try {
            next.startServer()
            server = next
        } catch (e: IOException) {
            Log.e(TAG, "Could not start the capture server", e)
        }
    }

    /** Only the hotspot gateway, which is the phone, may read captures. */
    private fun isPhone(address: String): Boolean {
        if (address == "127.0.0.1" || address == "::1") return true
        val phone = phoneHost()?.substringBefore(':') ?: return false
        val allowed = address == phone
        if (!allowed) Log.w(TAG, "Blocked capture request from $address")
        return allowed
    }

    private fun phoneHost(): String? = phoneOverride ?: dhcpIntToIp(wifi().dhcpInfo?.gateway ?: 0)

    @Suppress("DEPRECATION")
    private fun glassesIp(): String = dhcpIntToIp(wifi().connectionInfo?.ipAddress ?: 0).orEmpty()

    private fun wifi() = applicationContext.getSystemService(WifiManager::class.java)

    /** Test hooks for adb. Release builds ignore them, so no app can redirect face photos. */
    private fun readDebugExtras(intent: Intent) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return
        intent.getStringExtra(EXTRA_PHONE_HOST)?.let { phoneOverride = it }
        if (intent.getBooleanExtra(EXTRA_TEST_LINK, false) && ::phoneClient.isInitialized) {
            intent.removeExtra(EXTRA_TEST_LINK)
            linkChecks.execute { phoneClient.captureReady() }
        }
        val ssid = intent.getStringExtra(EXTRA_WIFI_SSID)
        val password = intent.getStringExtra(EXTRA_WIFI_PASSWORD)
        // Removed at once, so the password does not stay in the saved launch intent.
        intent.removeExtra(EXTRA_WIFI_SSID)
        intent.removeExtra(EXTRA_WIFI_PASSWORD)
        if (ssid != null && password != null) {
            wifiJoiner.join(ssid, password)
            listOf(4_000L, 9_000L, 15_000L).forEach { delay ->
                mainHandler.postDelayed(
                    { if (::controller.isInitialized) controller.refresh() },
                    delay,
                )
            }
        }
    }

    private fun requestMissingPermissions() {
        val missing = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun wake() {
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        mainHandler.removeCallbacks(dimScreen)
        mainHandler.postDelayed(dimScreen, DIM_AFTER_MS)
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private companion object {
        const val TAG = "Recall"
        const val PREF_VOICE = "voice"
        const val EXTRA_PHONE_HOST = "phone_host"
        const val EXTRA_PAIR_MOUSE = "pair_mouse"
        const val EXTRA_TEST_LINK = "test_link"
        const val EXTRA_WIFI_SSID = "wifi_ssid"
        const val EXTRA_WIFI_PASSWORD = "wifi_password"
        const val VOICE_MODEL = "vosk-model-small-en-us-0.15"
        const val PADDING = 24
        const val DIVIDER_PX = 2
        const val LINK_CHECK_S = 5L
        const val DIM_AFTER_MS = 45_000L
        const val DIM_BRIGHTNESS = 0.15f
        val PERMISSIONS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
