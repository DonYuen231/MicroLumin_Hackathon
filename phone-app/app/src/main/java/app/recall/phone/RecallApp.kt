package app.recall.phone

import android.app.Application
import app.recall.phone.data.PeopleRepository
import app.recall.phone.data.RecallDatabase
import app.recall.phone.data.SettingsStore
import app.recall.phone.hub.CaptureProcessor
import app.recall.phone.hub.Notifier
import app.recall.phone.hub.PhoneServer
import app.recall.phone.hub.Recognizer
import app.recall.phone.net.BackendClient
import app.recall.phone.net.GlassesClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    /** Outlives any screen, so leaving Captures does not cancel processing. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app)
    val people = PeopleRepository(RecallDatabase.create(app).dao(), app.filesDir)
    val backend = BackendClient { settings.current() }
    private val glasses = GlassesClient { settings.current().glassesAddress }
    private val notifier = Notifier(app)
    val captures = CaptureProcessor(
        app.cacheDir,
        people,
        backend,
        glasses,
        settings,
        onSaved = notifier::profileSaved,
    )
    val server = PhoneServer(Recognizer(people, backend, settings), settings)
}

class RecallApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch { container.settings.applyProvisioning() }
    }
}
