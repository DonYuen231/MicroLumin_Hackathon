package app.recall.phone

import android.app.Application
import android.util.Log
import app.recall.phone.data.PeopleRepository
import app.recall.phone.data.RecallDatabase
import app.recall.phone.data.SettingsStore
import app.recall.phone.hub.CaptureProcessor
import app.recall.phone.hub.FollowUpScheduler
import app.recall.phone.hub.Notifier
import app.recall.phone.hub.PhoneServer
import app.recall.phone.hub.Recognizer
import app.recall.phone.net.BackendClient
import app.recall.phone.net.GlassesClient
import kotlinx.coroutines.CancellationException
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
    val notifier = Notifier(app)
    private val followUps = FollowUpScheduler(app)
    val captures = CaptureProcessor(
        app.cacheDir,
        people,
        backend,
        glasses,
        settings,
        onSaved = { saved ->
            notifier.profileSaved(saved.personId, saved.profile.name, saved.updated)
            // The person is already saved, so a scheduling problem must not fail the capture.
            try {
                followUps.schedule(
                    saved.personId,
                    saved.profile,
                    saved.metAt,
                    settings.current().demoSpeed,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("RecallFollowUp", "Could not schedule: ${e.javaClass.simpleName}")
            }
        },
    )
    val server = PhoneServer(
        Recognizer(people, backend, settings),
        settings,
        onCaptureReady = { scope.launch { captures.syncInBackground() } },
    )
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
